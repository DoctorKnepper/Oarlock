package com.doctorknepper.oarlock.core

/** Parsed template call: {{name|positional|key=value}}. Values are raw wikitext. */
class Template(val name: String, val positional: List<String>, val named: Map<String, String>, val raw: String) {
    /** 1-based positional parameter, also honouring explicit "1=" style names. */
    fun arg(i: Int): String? = named[i.toString()] ?: positional.getOrNull(i - 1)
    fun get(vararg keys: String): String? {
        for (k in keys) named[k]?.let { if (it.isNotBlank()) return it }
        return null
    }
    val key: String get() = normalizeName(name)

    companion object {
        fun normalizeName(n: String): String {
            var s = n.trim().replace('_', ' ').replace(Regex("\\s+"), " ").lowercase()
            if (s.startsWith("template:")) s = s.substring(9).trim()
            return s
        }
    }
}

object WikiScan {
    /** Finds the end index (exclusive) of a balanced construct starting at [start] with [open]/[close]. */
    fun matchBraces(s: String, start: Int): Int {
        // start points at "{{"; handles nested {{ }} and {{{ }}}
        var depth = 0
        var i = start
        while (i < s.length - 1) {
            val c = s[i]
            if (c == '{' && s[i + 1] == '{') { depth++; i += 2; continue }
            if (c == '}' && s[i + 1] == '}') {
                depth--; i += 2
                if (depth == 0) return i
                continue
            }
            i++
        }
        return -1
    }

    fun matchBrackets(s: String, start: Int): Int {
        // start points at "[["
        var depth = 0
        var i = start
        while (i < s.length - 1) {
            val c = s[i]
            if (c == '[' && s[i + 1] == '[') { depth++; i += 2; continue }
            if (c == ']' && s[i + 1] == ']') {
                depth--; i += 2
                if (depth == 0) return i
                continue
            }
            if (c == '\n' && depth > 0 && i + 1 < s.length && s[i + 1] == '\n') return -1
            i++
        }
        return -1
    }

    /** Splits on '|' that is not nested inside {{ }}, [[ ]] or a table's {| |}. */
    fun splitPipes(s: String): List<String> {
        val out = ArrayList<String>()
        var depthB = 0; var depthL = 0
        var last = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val n = if (i + 1 < s.length) s[i + 1] else '\u0000'
            when {
                c == '{' && n == '{' -> { depthB++; i += 2; continue }
                c == '}' && n == '}' && depthB > 0 -> { depthB--; i += 2; continue }
                c == '[' && n == '[' -> { depthL++; i += 2; continue }
                c == ']' && n == ']' && depthL > 0 -> { depthL--; i += 2; continue }
                c == '|' && depthB == 0 && depthL == 0 -> { out.add(s.substring(last, i)); last = i + 1 }
            }
            i++
        }
        out.add(s.substring(last))
        return out
    }

    fun parseTemplate(inner: String): Template {
        val parts = splitPipes(inner)
        val name = parts[0]
        val pos = ArrayList<String>()
        val named = LinkedHashMap<String, String>()
        for (p in parts.drop(1)) {
            val eq = findTopLevelEquals(p)
            if (eq > 0) {
                val k = p.substring(0, eq).trim().lowercase().replace(' ', '_')
                if (k.isNotEmpty() && !k.contains('\n') && k.length < 60) { named[k] = p.substring(eq + 1).trim(); continue }
            }
            pos.add(p)
        }
        return Template(name, pos, named, inner)
    }

    private fun findTopLevelEquals(p: String): Int {
        var depth = 0
        for (i in p.indices) {
            val c = p[i]
            if (c == '{' || c == '[') depth++
            else if (c == '}' || c == ']') depth--
            else if (c == '=' && depth <= 0) return i
            else if (c == '<') return -1
        }
        return -1
    }

    /**
     * Replaces every top-level template in [text] with [handler]'s result. Nested templates are left
     * inside parameters for the handler to expand on demand.
     */
    fun mapTemplates(text: String, handler: (Template) -> String): String {
        if (!text.contains("{{")) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val s = text.indexOf("{{", i)
            if (s < 0) { sb.append(text, i, text.length); break }
            // "{{{param}}}" in article text is unusual; treat it as plain text
            sb.append(text, i, s)
            val e = matchBraces(text, s)
            if (e < 0) { sb.append(text, s, text.length); break }
            val inner = text.substring(s + 2, e - 2)
            if (inner.startsWith("{") && inner.endsWith("}")) {
                // {{{1|default}}} style parameter: keep default if present
                val pp = splitPipes(inner.removePrefix("{").removeSuffix("}"))
                sb.append(pp.getOrNull(1) ?: "")
            } else {
                sb.append(handler(parseTemplate(inner)))
            }
            i = e
        }
        return sb.toString()
    }
}

/** Template names whose output adds nothing offline (citations, maintenance tags, navigation boxes...). */
object TemplateRules {
    private val dropExact = setOf(
        "short description", "use dmy dates", "use mdy dates", "use british english", "use american english",
        "use canadian english", "use australian english", "use indian english", "engvar", "featured article",
        "good article", "authority control", "defaultsort", "reflist", "notelist", "notelist-ua", "notelist-lr",
        "notelist-lg", "refbegin", "refend", "references", "citation needed", "cn", "fact", "clarify", "when",
        "who", "which", "dubious", "by whom", "failed verification", "better source needed", "according to whom",
        "vague", "sfn", "sfnm", "sfnp", "sfnmp", "harvnb", "harv", "harvp", "harvtxt", "rp", "efn", "efn-ua",
        "efn-lr", "efn-lg", "r", "refn", "webarchive", "citation", "commons", "commons category", "commonscat",
        "commons-inline", "commons category-inline", "wiktionary", "wikiquote", "wikisource", "wikibooks",
        "wikivoyage", "wikispecies", "sister project links", "spoken wikipedia", "multiple issues", "taxonbar",
        "coord missing", "toc limit", "toc right", "toc left", "clear", "clr", "-", "clear left", "clear right",
        "col div", "div col", "div col end", "col-begin", "col-end", "col-break", "col-2", "col-3", "colend",
        "refimprove", "unreferenced", "more citations needed", "more citations needed section", "one source",
        "primary sources", "pp", "pp-move", "pp-semi", "pp-protected", "pp-vandalism", "pp-move-indef",
        "pp-semi-indef", "pp-pc1", "pp-sock", "pp-30-500", "redirect", "redirect2", "redirect-multi",
        "redirect-distinguish", "about", "for", "other uses", "distinguish", "other people", "other places",
        "for multi", "selfref", "self-reference", "broader", "portal", "portal bar", "portal-inline", "subject bar",
        "stack", "stack end", "stub", "ipac-en", "ipac-uk", "ipac-us", "respell", "audio", "audio-ipa", "listen",
        "pronunciation", "lang-help", "s-start", "s-end", "s-bef", "s-aft", "s-ttl", "s-off", "s-par", "s-gov",
        "s-reg", "s-hon", "s-ach", "s-mil", "s-new", "s-inc", "s-vac", "s-break", "succession box", "navbox",
        "navboxes", "navbox with collapsible groups", "sidebar", "sidebar with collapsible lists", "update",
        "update after", "inflation-fn", "inflation/fn", "cbignore", "dead link", "sic", "page needed", "full citation needed",
        "cleanup", "cleanup rewrite", "copy edit", "advert", "peacock", "weasel", "pov", "npov", "tone", "essay-like",
        "expand section", "empty section", "unreferenced section", "original research", "verify source", "self-published source",
        "anchors", "good article", "featured list", "wikidata property", "official website", "osm", "geographic location",
        "excerpt", "transcluded section", "image requested", "reqphoto", "talk header", "edit section", "dablink",
        "hatnote", "further information", "location map", "location map many", "mycomorphbox", "climate chart",
        "weather box", "graph:chart", "graph:lines", "barchart", "bar chart", "pie chart", "timeline", "chart",
        "chem2", "pagenumber", "nowrap end", "nowrap begin", "end", "end box", "box-header", "box-footer", "start box",
        "notice", "warning", "reflist-talk", "use list-defined references", "list-defined references", "verification needed",
        "outdated", "in use", "under construction", "current", "recentism", "examples", "to do"
    )
    private val dropPrefixes = listOf(
        "cite ", "citation", "pp-", "infobox mapframe", "navbox", "use ", "cleanup", "more citations", "portal",
        "s-", "succession", "sidebar", "commons", "sister", "taxonbar", "authority", "#", "coord missing",
        "geobox ", "wikt", "multiple issues", "user ", "speciesbox/", "wikidata", "webarchive", "sfn", "harv",
        "efn", "ref ", "refn", "lang-help", "ipac-", "audio", "pronunciation"
    )
    private val dropSuffixes = listOf(" navbox", " sidebar", " topics", " portal", " footer", " navigation", "-stub", " stub", " series")

    fun isDropped(key: String): Boolean {
        if (key in dropExact) return true
        if (key.contains(':') && !key.startsWith("lc:") && !key.startsWith("uc:") && !key.startsWith("formatnum:") &&
            !key.startsWith("ucfirst:") && !key.startsWith("lcfirst:")) return true // DEFAULTSORT:, DISPLAYTITLE:, #if:
        if (dropPrefixes.any { key.startsWith(it) }) return true
        if (dropSuffixes.any { key.endsWith(it) }) return true
        return false
    }

    fun isInfobox(key: String): Boolean =
        key.startsWith("infobox") || key == "taxobox" || key == "speciesbox" || key == "automatic taxobox" ||
            key == "subspeciesbox" || key == "virusbox" || key == "hybridbox" || key == "infraspeciesbox" ||
            key.startsWith("geobox") || key == "chembox" || key == "drugbox" || key == "settlement" ||
            key.endsWith(" infobox")
}
