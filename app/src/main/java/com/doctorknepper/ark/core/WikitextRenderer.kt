package com.doctorknepper.ark.core

import java.net.URLEncoder

data class TocEntry(val level: Int, val id: String, val title: String)

data class RenderedArticle(
    val title: String,
    val html: String,
    val toc: List<TocEntry>,
    val lat: Double?,
    val lon: Double?,
    val images: List<String>,
)

/**
 * Converts MediaWiki wikitext into clean, self-contained HTML for offline reading.
 * It does not expand real templates (those live in other pages); instead it understands the
 * templates that carry visible content and drops citation, maintenance and navigation clutter.
 */
class WikitextRenderer(
    private val linkPrefix: String = "https://ark.local/wiki/",
    private val imagePrefix: String = "https://ark.local/wimg/",
) {
    private val protected = ArrayList<String>()
    private var lat: Double? = null
    private var lon: Double? = null
    private val images = LinkedHashSet<String>()
    private val usedIds = HashSet<String>()

    private val blockPh = HashSet<Int>()

    private fun protect(html: String): String { protected.add(html); return "\u0002${protected.size - 1}\u0002" }

    /** Placeholder for block-level output (figures, infoboxes, quotes) that must not sit inside a paragraph. */
    private fun protectBlock(html: String): String { protected.add(html); blockPh.add(protected.size - 1); return "\u0002${protected.size - 1}\u0002" }

    private fun restore(s: String): String {
        var out = s
        var guard = 0
        while (out.indexOf('\u0002') >= 0 && guard++ < 6) {
            out = PH.replace(out) { m -> protected.getOrNull(m.groupValues[1].toInt()) ?: "" }
        }
        return out
    }

    fun render(title: String, wikitext: String): RenderedArticle {
        protected.clear(); blockPh.clear(); images.clear(); usedIds.clear(); lat = null; lon = null
        var t = preprocess(wikitext)
        t = expandTemplates(t, topLevel = true)
        t = fileLinks(t)
        t = dropTailSections(t)
        val toc = ArrayList<TocEntry>()
        val body = renderBlocks(t, toc)
        val html = restore(body.replace(EMPTY_PARENS, ""))
        return RenderedArticle(title, html, toc, lat, lon, images.toList())
    }

    // ---------------------------------------------------------------- preprocessing

    private fun preprocess(src: String): String {
        var t = src.replace("\r\n", "\n")
        t = COMMENT.replace(t, "")
        // Verbatim regions first so nothing inside is interpreted.
        t = NOWIKI.replace(t) { m -> protect(esc(m.groupValues[1])) }
        t = PRE.replace(t) { m -> protect("<pre>" + esc(m.groupValues[2].trim('\n')) + "</pre>") }
        t = SYNTAX.replace(t) { m -> protect("<pre class=\"code\">" + esc(m.groupValues[2].trim('\n')) + "</pre>") }
        t = MATH.replace(t) { m -> protect("<code class=\"math\">" + esc(m.groupValues[2].trim()) + "</code>") }
        t = CHEM.replace(t) { m -> protect("<code class=\"math\">" + esc(m.groupValues[2].trim()) + "</code>") }
        t = REF_SELF.replace(t, "")
        t = REF_BLOCK.replace(t, "")
        t = REFERENCES.replace(t, "")
        t = REFERENCES_BLOCK.replace(t, "")
        t = DROP_TAGS.replace(t, "")
        t = GALLERY.replace(t) { m -> gallery(m.groupValues[2]) }
        t = IMAGEMAP.replace(t) { m -> m.groupValues[2].trim().lineSequence().firstOrNull()?.let { "[[" + it + "]]" } ?: "" }
        t = POEM.replace(t) { m -> "<div class=\"poem\">" + m.groupValues[2].trim('\n').replace("\n", "<br>\n") + "</div>" }
        t = MAGIC.replace(t, "")
        return t
    }

    private fun gallery(body: String): String {
        val items = body.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = line.split('|', limit = 2)
            var name = parts[0].trim()
            name = name.substringAfter(':', name).takeIf { parts[0].contains(':') } ?: name
            if (name.isBlank()) null else figure(name, parts.getOrNull(1)?.let { inline(expandTemplates(it)) } ?: "", "gallery-item")
        }
        if (items.isEmpty()) return ""
        return "\n\n" + protectBlock("<div class=\"gallery\">" + items.joinToString("") + "</div>") + "\n\n"
    }

    // ---------------------------------------------------------------- templates

    private fun expandTemplates(text: String, topLevel: Boolean = false): String =
        WikiScan.mapTemplates(text) { tpl -> handleTemplate(tpl, topLevel) }

    private fun ex(s: String?): String = if (s == null) "" else expandTemplates(s).trim()

    private fun handleTemplate(t: Template, topLevel: Boolean): String {
        val k = t.key
        // Content-bearing templates first; anything else is checked against the drop rules.
        when (k) {
            "!" -> return "|"
            "!!" -> return "||"
            "=" -> return "="
            "'" -> return "'"
            "nbsp", "&nbsp" -> return "&nbsp;"
            "spaces", "space" -> return "&nbsp;".repeat((t.arg(1)?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 8))
            "snd", "spnd", "sndash", "ndash", "–" -> return " – "
            "mdash", "—", "dash" -> return "—"
            "pipe" -> return "|"
            "colon" -> return ":"
            "·", "dot", "middot", "bull", "bullet", "•" -> return " · "
            "nowrap", "nobr", "no wrap", "small", "smaller", "big", "bigger", "larger", "large", "em", "strong", "tt",
            "nobold", "noitalic", "mono", "monospace", "smallcaps", "sc", "sc1", "midsize", "small caps", "plain",
            "nowraplinks", "nobreak", "keypress", "key press", "kbd", "var", "mvar", "math", "char", "bigmath",
            "underline", "u", "overline", "strikethrough", "strike", "s", "del", "ins", "highlight", "background color",
            "nowrap begin", "angle bracket", "center", "centre", "left", "right", "resize", "font", "font color",
            "color", "colour", "text", "vanchor", "visible anchor", "linktext", "sic?", "not a typo", "proper name",
            "serif", "sans-serif", "script", "transl", "transliteration", "lang", "langx", "ill", "interlanguage link",
            "interlanguage link multi", "nihongo", "nihongo2", "nihongo3", "chem", "chem2", "ce", "chemf",
            "tooltip", "abbr", "abbrlink", "rn", "smallsup", "hidden", "titled", "code", "samp", "tag", "lc", "uc",
            "ucfirst", "lcfirst", "allcaps", "smalldiv", "noprint", "sub", "sup", "subsup", "su", "pslink" ->
                return simpleWrap(t, k)
            "convert", "cvt", "convinfobox", "convert/flip" -> return convert(t)
            "frac", "sfrac", "fraction", "frac2" -> return frac(t)
            "val" -> return ex(t.arg(1)) + (t.get("u", "ul")?.let { " " + ex(it) } ?: "")
            "us$", "usd", "us dollar" -> return "US$" + ex(t.arg(1))
            "cad", "ca$" -> return "CA$" + ex(t.arg(1))
            "aud", "a$" -> return "A$" + ex(t.arg(1))
            "gbp", "£" -> return "£" + ex(t.arg(1))
            "euro", "€", "eur" -> return "€" + ex(t.arg(1))
            "jpy", "yen" -> return "¥" + ex(t.arg(1))
            "inr", "₹" -> return "₹" + ex(t.arg(1))
            "circa", "c.", "ca." -> return "c. " + ex(t.arg(1)) + (t.arg(2)?.let { " – c. " + ex(it) } ?: "")
            "fl.", "floruit" -> return "fl. " + ex(t.arg(1))
            "reign", "r." -> return "r. " + listOfNotNull(t.arg(1), t.arg(2)).joinToString(" – ") { ex(it) }
            "lit", "literally", "lit." -> return "lit. ‘" + t.positional.joinToString(", ") { ex(it) } + "’"
            "as of" -> return "As of " + dateText(t.arg(1), t.arg(2), t.arg(3))
            "isbn" -> return "ISBN " + t.positional.joinToString(", ") { ex(it) }
            "oclc" -> return "OCLC " + ex(t.arg(1))
            "birth date", "birth date and age", "bda", "dob", "death date", "start date", "end date", "start date and age",
            "death date and age", "dda", "film date", "release date", "date", "birth-date", "death-date", "birth year and age",
            "death year and age", "birth-date and age", "end date and age", "date and age" -> return dateTemplate(t)
            "age", "age in years", "years ago" -> return ""
            "coord", "coor", "coor d", "coor dm", "location" -> return coord(t)
            "main", "main article", "main articles", "see also", "further", "details", "also", "see", "further2" -> return hatnote(t, k, topLevel)
            "quote", "blockquote", "cquote", "quote box", "pull quote", "quotation", "rquote", "bquote", "centered pull quote",
            "quote frame", "poem quote" -> return blockquote(t)
            "plainlist", "flatlist", "plain list", "flat list", "endplainlist", "endflatlist", "startplainlist", "startflatlist" ->
                return t.arg(1)?.let { "\n" + ex(it) + "\n" } ?: ""
            "ubl", "unbulleted list", "ubil", "unbulleted indent list", "plainlist/item" ->
                return t.positional.filter { it.isNotBlank() }.joinToString("<br>") { ex(it) }
            "hlist", "flat list item", "hlist-comma" -> return t.positional.filter { it.isNotBlank() }.joinToString(" · ") { ex(it) }
            "bulleted list", "bulletted list", "blist", "ordered list", "olist" -> {
                val items = t.positional.filter { it.isNotBlank() }
                val tag = if (k.startsWith("o")) "ol" else "ul"
                return protect("<$tag>" + items.joinToString("") { "<li>" + inline(ex(it)) + "</li>" } + "</$tag>")
            }
            "multiple image", "multiple images", "image array", "gallery", "photomontage", "image frame", "css image crop", "annotated image",
            "superimpose", "image label begin" -> return multipleImage(t)
            "formula", "math block", "block indent", "indent", "in5", "in", "pad" ->
                return t.arg(1)?.let { ex(it) } ?: ""
            "flagicon", "flagdeco", "flag icon", "flagicon image", "flag decoration" -> return ""
            "flag", "flagcountry", "flagu", "flag country", "country", "flaglink" -> return ex(t.arg(1))
            "lang-en", "langx-en" -> return ex(t.arg(1))
            "ipa", "ipa-all", "ipa audio" -> return "/" + ex(t.arg(1)) + "/"
            "morse" , "morse code" -> return protect("<span class=\"mono\">" + esc(t.positional.joinToString(" ") { it.trim() }) + "</span>")
            "ordinal" -> return ex(t.arg(1)) + ordinal(t.arg(1)?.trim()?.toIntOrNull())
            "rail-interchange", "rint", "ubl-item", "nbhyph" -> return if (k == "nbhyph") "‑" else ""
            "x mark", "cross", "xmark", "no" -> return "✗"
            "check mark", "tick", "y", "yes", "checkmark", "done" -> return "✓"
            "okay" -> return "OK"
            "nowiki" -> return esc(t.arg(1) ?: "")
            "quotation mark", "\"" -> return "“" + ex(t.arg(1)) + "”"
            "sclass", "sclass-" -> return ex(t.arg(1)) + "-class"
            "ship", "sclass2", "hms", "uss", "hmcs", "hmas", "ss" -> return (if (k == "ship") ex(t.arg(1)) + " " + ex(t.arg(2)) else k.uppercase() + " " + ex(t.arg(1)))
            "taxon", "taxon italics" -> return "''" + ex(t.arg(1)) + "''"
            "sp", "genus", "species", "specieslist" -> return "''" + t.positional.joinToString(" ") { ex(it) } + "''"
            "endash" -> return "–"
            "inflation" -> return ""
            "large number", "formatnum" -> return ex(t.arg(1))
            "bc", "bce" -> return ex(t.arg(1)) + " BC"
            "ad", "ce" -> return "AD " + ex(t.arg(1))
            "hair space", "hsp", "thinsp", "thin space", "zwsp", "wbr" -> return ""
            "abbreviation" -> return ex(t.arg(1))
        }
        if (k.startsWith("lang-") || k.startsWith("langx")) return ex(t.positional.lastOrNull { it.isNotBlank() } ?: t.arg(1))
        if (k.startsWith("ipa-") || k.startsWith("ipa ")) return ex(t.arg(1))
        if (k.startsWith("lc:") || k.startsWith("uc:") || k.startsWith("formatnum:") || k.startsWith("ucfirst:") || k.startsWith("lcfirst:")) {
            val v = ex(t.name.substringAfter(':'))
            return when {
                k.startsWith("lc:") -> v.lowercase(); k.startsWith("uc:") -> v.uppercase()
                k.startsWith("ucfirst:") -> v.replaceFirstChar { it.uppercase() }
                k.startsWith("lcfirst:") -> v.replaceFirstChar { it.lowercase() }
                else -> v
            }
        }
        if (TemplateRules.isInfobox(k)) return if (topLevel) infobox(t) else ""
        if (k.startsWith("cite") || TemplateRules.isDropped(k)) return ""
        if (k.endsWith(" icon") || k.startsWith("flag")) return ""
        // Unknown template: keep simple single-argument text wrappers, drop the rest.
        if (t.named.isEmpty() && t.positional.size == 1 && k.length <= 12 && !t.positional[0].contains('\n') && t.positional[0].length < 200)
            return ex(t.positional[0])
        return ""
    }

    private fun simpleWrap(t: Template, k: String): String {
        val text = when (k) {
            "lang", "langx", "transl", "transliteration" -> t.arg(2) ?: t.arg(1)
            "ill", "interlanguage link", "interlanguage link multi" -> t.get("lt")?.let { "[[" + t.arg(1) + "|" + it + "]]" } ?: t.arg(1)?.let { "[[" + it + "]]" }
            "nihongo", "nihongo2", "nihongo3" -> t.arg(1)
            "tooltip", "abbr", "abbrlink" -> t.arg(1)
            "resize", "font", "font color", "color", "colour", "background color", "highlight" -> t.get("text") ?: t.positional.lastOrNull()
            "vanchor", "visible anchor" -> t.arg(1)
            "tag" -> t.arg(1)?.let { "&lt;" + it + "&gt;" }
            "subsup" -> t.arg(1)
            else -> t.get("text", "1") ?: t.arg(1)
        } ?: return ""
        val v = ex(text)
        return when (k) {
            "sub" -> "<sub>$v</sub>"
            "sup", "smallsup" -> "<sup>$v</sup>"
            "su" -> "<sup>" + ex(t.get("p")) + "</sup><sub>" + ex(t.get("b")) + "</sub>"
            "subsup" -> v + "<sub>" + ex(t.arg(2)) + "</sub><sup>" + ex(t.arg(3)) + "</sup>"
            "small", "smaller", "smalldiv" -> "<small>$v</small>"
            "big", "bigger", "larger", "large" -> "<big>$v</big>"
            "em" -> "<em>$v</em>"
            "strong" -> "<strong>$v</strong>"
            "code", "tt", "mono", "monospace", "kbd", "keypress", "key press", "samp" -> "<code>$v</code>"
            "var", "mvar" -> "<i>$v</i>"
            "lc" -> v.lowercase()
            "uc", "allcaps" -> v.uppercase()
            "ucfirst" -> v.replaceFirstChar { it.uppercase() }
            "lcfirst" -> v.replaceFirstChar { it.lowercase() }
            "strikethrough", "strike", "s", "del" -> "<s>$v</s>"
            "underline", "u", "ins" -> "<u>$v</u>"
            "overline" -> "<span style=\"text-decoration:overline\">$v</span>"
            "smallcaps", "sc", "sc1", "small caps" -> "<span style=\"font-variant:small-caps\">$v</span>"
            "chem", "chem2", "ce", "chemf" -> v.replace(Regex("(?<=[A-Za-z)\\]])(\\d+)"), "<sub>$1</sub>")
            else -> v
        }
    }

    private fun convert(t: Template): String {
        val p = t.positional.map { it.trim() }
        if (p.isEmpty()) return ""
        val ranges = setOf("-", "–", "to", "and", "or", "x", "×", "by", "+", "to(-)", "&ndash;")
        val sb = StringBuilder(ex(p[0]))
        var i = 1
        while (i + 1 < p.size && p[i] in ranges) {
            sb.append(if (p[i] == "-" || p[i] == "&ndash;" || p[i] == "to(-)") "–" else " ${p[i]} ").append(ex(p[i + 1])); i += 2
        }
        val unit = p.getOrNull(i) ?: return sb.toString()
        return sb.append(" ").append(unitLabel(unit)).toString()
    }

    private fun unitLabel(u: String): String = when (u) {
        "C", "°C", "degC" -> "°C"; "F", "°F", "degF" -> "°F"; "K" -> "K"
        "sqmi", "mi2" -> "sq mi"; "km2", "sqkm" -> "km²"; "m2" -> "m²"; "ft2", "sqft" -> "sq ft"; "ha" -> "ha"; "acre" -> "acres"
        "mph" -> "mph"; "km/h", "kph" -> "km/h"; "m/s" -> "m/s"; "kn" -> "knots"
        "ft" -> "ft"; "m" -> "m"; "km" -> "km"; "mi" -> "mi"; "in" -> "in"; "cm" -> "cm"; "mm" -> "mm"; "yd" -> "yd"; "nmi" -> "nmi"
        "kg" -> "kg"; "lb" -> "lb"; "g" -> "g"; "oz" -> "oz"; "t" -> "t"; "ST" -> "short tons"; "LT" -> "long tons"; "st" -> "st"
        "L", "l" -> "L"; "ml", "mL" -> "mL"; "USgal" -> "US gal"; "impgal" -> "imp gal"; "cuft" -> "cu ft"; "m3" -> "m³"
        "hp" -> "hp"; "kW" -> "kW"; "MW" -> "MW"; "mi2km2" -> "sq mi"
        else -> u.replace("2", "²").replace("3", "³")
    }

    private fun frac(t: Template): String {
        val p = t.positional.map { ex(it) }
        return when (p.size) {
            1 -> "1⁄${p[0]}"
            2 -> "${p[0]}⁄${p[1]}"
            0 -> ""
            else -> "${p[0]} ${p[1]}⁄${p[2]}"
        }
    }

    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private fun dateText(y: String?, m: String?, d: String?): String {
        val yy = y?.trim().orEmpty()
        val mm = m?.trim()?.toIntOrNull()?.let { months.getOrNull(it - 1) } ?: m?.trim().orEmpty()
        val dd = d?.trim().orEmpty()
        return listOf(mm, dd).filter { it.isNotEmpty() }.joinToString(" ") + (if (mm.isNotEmpty() || dd.isNotEmpty()) ", " else "") + yy
    }

    private fun dateTemplate(t: Template): String {
        val p = t.positional.map { it.trim() }.filter { it.isNotEmpty() && !it.contains('=') }
        if (p.isEmpty()) return ""
        if (p[0].toIntOrNull() != null && p[0].length >= 3) return dateText(p[0], p.getOrNull(1), p.getOrNull(2)).trimStart(',', ' ')
        return ex(p[0])
    }

    private fun coord(t: Template): String {
        val nums = ArrayList<Double>()
        var latSign = 1.0; var lonSign = 1.0
        var seenLat = false
        for (raw in t.positional) {
            val v = raw.trim()
            when {
                v.equals("N", true) -> seenLat = true
                v.equals("S", true) -> { latSign = -1.0; seenLat = true }
                v.equals("E", true) -> {}
                v.equals("W", true) -> lonSign = -1.0
                v.toDoubleOrNull() != null -> nums.add(v.toDouble())
            }
        }
        val split = if (seenLat) {
            val idx = t.positional.indexOfFirst { it.trim().equals("N", true) || it.trim().equals("S", true) }
            t.positional.take(idx).count { it.trim().toDoubleOrNull() != null }
        } else nums.size / 2
        if (nums.size < 2 || split <= 0 || split >= nums.size) return ""
        fun dms(a: List<Double>) = a.getOrElse(0) { 0.0 } + a.getOrElse(1) { 0.0 } / 60 + a.getOrElse(2) { 0.0 } / 3600
        val la = latSign * dms(nums.subList(0, split))
        val lo = lonSign * dms(nums.subList(split, nums.size))
        if (la !in -90.0..90.0 || lo !in -180.0..180.0) return ""
        val display = t.get("display")?.lowercase().orEmpty()
        if (lat == null || display.contains("t")) { lat = la; lon = lo }
        val text = "%.4f°%s %.4f°%s".format(kotlin.math.abs(la), if (la >= 0) "N" else "S", kotlin.math.abs(lo), if (lo >= 0) "E" else "W")
        if (display == "title" || display == "t") return ""
        return protect("<a class=\"coord\" href=\"https://ark.local/map?lat=$la&lon=$lo\">$text</a>")
    }

    private fun hatnote(t: Template, k: String, topLevel: Boolean): String {
        val targets = t.positional.filter { it.isNotBlank() }.map { ex(it) }.filter { it.isNotBlank() }
        if (targets.isEmpty()) return ""
        val labels = targets.mapIndexed { i, tgt -> t.get("l${i + 1}", "label ${i + 1}")?.let { "[[$tgt|$it]]" } ?: "[[$tgt]]" }
        val lead = when (k) {
            "main", "main article", "main articles" -> if (targets.size > 1) "Main articles: " else "Main article: "
            "further", "further2", "details" -> "Further information: "
            else -> "See also: "
        }
        val html = "<div class=\"hatnote\">" + lead + inline(labels.joinToString(", ")) + "</div>"
        return if (topLevel) "\n" + protectBlock(html) + "\n" else protect(html)
    }

    private fun blockquote(t: Template): String {
        val text = t.get("text", "quote", "1") ?: t.arg(1) ?: return ""
        val by = t.get("author", "sign", "2") ?: t.arg(2)
        val src = t.get("source", "title", "3") ?: t.arg(3)
        val cite = listOfNotNull(by?.takeIf { it.isNotBlank() }?.let { ex(it) }, src?.takeIf { it.isNotBlank() }?.let { ex(it) }).joinToString(", ")
        val html = "<blockquote>" + inline(ex(text)).replace("\n", "<br>") + (if (cite.isNotEmpty()) "<div class=\"qcite\">— ${inline(cite)}</div>" else "") + "</blockquote>"
        return "\n" + protectBlock(html) + "\n"
    }

    private fun multipleImage(t: Template): String {
        val figs = ArrayList<String>()
        for (i in 1..12) {
            val img = t.get("image$i", "image_$i", "image $i") ?: if (i == 1) t.get("image", "file") else null
            if (img == null) { if (i > 2) break else continue }
            val cap = t.get("caption$i", "caption_$i") ?: ""
            figs.add(figure(cleanFileName(img), inline(ex(cap)), "gallery-item"))
        }
        val footer = t.get("footer", "caption")?.let { "<div class=\"gcap\">" + inline(ex(it)) + "</div>" } ?: ""
        if (figs.isEmpty()) return ""
        return "\n" + protectBlock("<div class=\"gallery\">" + figs.joinToString("") + footer + "</div>") + "\n"
    }

    private fun cleanFileName(v: String): String {
        var s = v.trim()
        if (s.startsWith("[[")) s = s.removePrefix("[[").substringBefore('|').removeSuffix("]]")
        val c = s.indexOf(':')
        if (c in 1..9 && s.substring(0, c).lowercase() in setOf("file", "image", "datei", "fichier", "imagen")) s = s.substring(c + 1)
        return s.trim()
    }

    private val infoboxSkip = Regex(
        "^(image|image\\d|image_?size|imagesize|img_?size|image_?upright|upright|alt|image_?alt|caption|image_?caption|imagecaption|logo.*|" +
            "signature.*|module\\d*|embed|child|subbox|map.*|pushpin.*|coordinates|coord|coords|latd|latm|lats|latns|longd|longm|longs|longew|" +
            "mapframe.*|width|bodystyle|.*style|.*class|footnotes|name|official_name|native_name.*|image_skyline|image_flag|image_seal|" +
            "image_shield|image_blank_emblem|blank_emblem.*|seal.*|flag.*|shield.*|nickname_link|website|url|label\\d+|above|subheader.*|" +
            "data\\d+|header\\d+|title|color|colour|color_scheme|status_ref|status_system|regnum|taxon|authority|parent_authority|" +
            "display_parents|candidate|range_map.*|fossil_range|onlinebooks|qid|wikidata|footnote.*|smallimage|image_map.*|" +
            "short_description|area_footnotes|population_footnotes|elevation_footnotes|timezone_link|utc_offset.*_link|.*_ref|.*_note|" +
            "pronunciation|spouse.*type|signature_alt|signature_size|module.*|extra|post_nominals.*|order|office)$"
    )

    private fun infobox(t: Template): String {
        val heading = t.get("name", "official_name", "title", "taxon")?.let { inline(ex(it)) }
        val sb = StringBuilder("<aside class=\"infobox\">")
        if (heading != null && heading.isNotBlank()) sb.append("<div class=\"ib-title\">").append(heading).append("</div>")
        val img = t.get("image", "image1", "image_skyline", "photo", "image_name", "image_file", "img", "image_map", "map_image", "logo")
        if (img != null) {
            val name = cleanFileName(ex(img))
            if (name.isNotBlank() && !name.contains('{') && !name.contains('<')) {
                val cap = t.get("caption", "image_caption", "imagecaption", "map_caption") ?: ""
                sb.append(figure(name, inline(ex(cap)), "ib-figure"))
            }
        }
        val rows = StringBuilder()
        var count = 0
        // Generic infoboxes: label1/data1 pairs
        for (i in 1..60) {
            val label = t.get("label$i") ?: continue
            val data = t.get("data$i") ?: continue
            val v = inline(ex(data)).trim()
            if (v.isEmpty()) continue
            rows.append("<tr><th>").append(inline(ex(label))).append("</th><td>").append(v).append("</td></tr>")
            count++
        }
        // Coordinates hidden in coordinate params
        t.get("coordinates", "coords", "coord")?.let { ex(it) }
        val consumed = HashSet<String>()
        val pairRe = Regex("^(.*?)_?(type|title)(\\d*)$")
        for ((key, raw) in t.named) {
            if (count >= 40) break
            if (key in consumed || raw.isBlank() || infoboxSkip.matches(key) || key in infoboxNoise) continue
            // "subdivision_type1 = Province / subdivision_name1 = Ontario" -> "Province: Ontario"
            val pm = pairRe.matchEntire(key)
            if (pm != null) {
                val base = pm.groupValues[1]; val n = pm.groupValues[3]
                val partner = listOf("${base}_name$n", "${base}name$n", "${base}_date$n", "${base}date$n").firstOrNull { t.named.containsKey(it) }
                if (partner != null) {
                    consumed.add(partner)
                    val lbl = inline(ex(raw)).trim()
                    val v = inline(ex(t.named[partner])).trim()
                    if (lbl.isNotEmpty() && v.isNotEmpty()) {
                        rows.append("<tr><th>").append(lbl).append("</th><td>").append(v.replace("\n", "<br>")).append("</td></tr>"); count++
                    }
                    continue
                }
            }
            val v = inline(ex(raw)).trim()
            if (v.isEmpty() || v == "&nbsp;") continue
            rows.append("<tr><th>").append(esc(labelFor(key))).append("</th><td>").append(v.replace("\n", "<br>")).append("</td></tr>")
            count++
        }
        if (rows.isNotEmpty()) {
            val all = Regex("(?=<tr>)").split(rows.toString()).filter { it.isNotEmpty() }
            sb.append("<table>").append(all.take(8).joinToString("")).append("</table>")
            if (all.size > 8) sb.append("<details><summary>More facts (${all.size - 8})</summary><table>").append(all.drop(8).joinToString("")).append("</table></details>")
        }
        sb.append("</aside>")
        if (rows.isEmpty() && img == null) return ""
        return "\n" + protectBlock(sb.toString()) + "\n"
    }

    private val infoboxNoise = setOf("unit_pref", "pushpin_map", "mapsize", "image_upright", "timezone1_location", "blank_name_sec1",
        "blank_info_sec1", "blank1_name_sec1", "blank1_info_sec1", "registration_plate", "iso_code", "geocode", "type_name", "total_type",
        "seat_type", "parts_style", "leader_party", "area_magnitude", "demographics_type1",
        "demographics1_footnotes", "elevation_max_m", "elevation_min_m", "population_as_of",
        "population_density_km2", "area_land_km2", "area_water_km2", "area_urban_km2", "area_metro_km2", "area_rank",
        "population_rank", "population_density_rank", "population_metro", "population_urban", "population_density_urban_km2",
        "population_density_metro_km2", "population_demonym", "postal_code_type", "area_code_type", "website", "nrhp_type", "embedded",
        "image_size", "imagesize", "parts_type")

    private fun labelFor(key: String): String {
        val s = key.replace('_', ' ').replace(Regex("(\\d+)$"), " $1").trim()
        return s.replaceFirstChar { it.uppercase() }
    }

    private fun ordinal(n: Int?): String {
        if (n == null) return ""
        if (n % 100 in 11..13) return "th"
        return when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    }

    /** File links may span blank lines in their captions, so they are resolved before paragraphs are split. */
    private fun fileLinks(t: String): String {
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val st = t.indexOf("[[", i)
            if (st < 0) { sb.append(t, i, t.length); break }
            sb.append(t, i, st)
            val colon = t.indexOf(':', st)
            val ns = if (colon in (st + 2)..(st + 10)) t.substring(st + 2, colon).trim().lowercase() else ""
            if (ns !in fileNs) { sb.append("[["); i = st + 2; continue }
            var depth = 0; var j = st; var end = -1
            while (j < t.length - 1) {
                if (t[j] == '[' && t[j + 1] == '[') { depth++; j += 2; continue }
                if (t[j] == ']' && t[j + 1] == ']') { depth--; j += 2; if (depth == 0) { end = j; break }; continue }
                j++
            }
            if (end < 0 || end - st > 4000) { sb.append("[["); i = st + 2; continue }
            val inner = t.substring(st + 2, end - 2).replace(Regex("\\s*\\n\\s*"), " ")
            sb.append(link(inner, "").first)
            i = end
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- sections

    private val tailSections = setOf(
        "references", "notes", "footnotes", "citations", "sources", "bibliography", "external links", "further reading",
        "notes and references", "references and notes", "explanatory notes", "general and cited sources", "works cited",
        "cited sources", "general references", "specific references", "inline citations", "note", "reference", "external link",
        "online sources", "printed sources", "literature", "general sources", "cited works", "endnotes"
    )

    private fun dropTailSections(t: String): String {
        val lines = t.split('\n')
        val out = StringBuilder(t.length)
        var skipLevel = 0
        for (line in lines) {
            val h = HEADING.matchEntire(line.trim())
            if (h != null) {
                val level = h.groupValues[1].length
                val name = h.groupValues[2].trim().replace(Regex("''+"), "").lowercase()
                if (skipLevel > 0 && level <= skipLevel) skipLevel = 0
                if (skipLevel == 0 && name in tailSections) { skipLevel = level; continue }
            }
            if (skipLevel == 0) out.append(line).append('\n')
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- blocks

    private fun renderBlocks(t: String, toc: MutableList<TocEntry>): String {
        val out = StringBuilder(t.length + t.length / 4)
        val lines = t.split('\n')
        val para = StringBuilder()
        val listStack = StringBuilder() // characters of currently open list levels
        fun closeLists(to: Int = 0) {
            while (listStack.length > to) {
                when (listStack.last()) { '*' -> out.append("</li></ul>"); '#' -> out.append("</li></ol>"); else -> out.append("</dd></dl>") }
                listStack.setLength(listStack.length - 1)
            }
        }
        fun flushPara() {
            var s = para.toString().trim()
            if (s.contains('\u0002')) {
                val hoisted = StringBuilder()
                s = PH.replace(s) { m -> if (m.groupValues[1].toInt() in blockPh) { hoisted.append(m.value); "" } else m.value }.trim()
                out.append(hoisted)
            }
            if (s.isNotEmpty()) {
                val html = inline(s)
                if (html.isNotBlank()) {
                    // Paragraphs that are only block placeholders stay unwrapped
                    if (BLOCK_ONLY.matches(html.trim())) out.append(html) else out.append("<p>").append(html).append("</p>")
                }
            }
            para.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimEnd()
            // Tables
            if (trimmed.trimStart().startsWith("{|")) {
                flushPara(); closeLists()
                val sb = StringBuilder()
                var depth = 0
                while (i < lines.size) {
                    val l = lines[i].trim()
                    if (l.startsWith("{|")) depth++
                    if (l.startsWith("|}")) depth--
                    sb.append(lines[i]).append('\n')
                    i++
                    if (depth <= 0) break
                }
                out.append(table(sb.toString()))
                continue
            }
            val h = HEADING.matchEntire(trimmed)
            if (h != null) {
                flushPara(); closeLists()
                val level = h.groupValues[1].length.coerceIn(2, 6)
                val text = inline(h.groupValues[2].trim())
                val plain = restore(text).replace(TAGS, "").trim()
                var id = "s-" + plain.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                while (!usedIds.add(id)) id += "-"
                if (level <= 3) toc.add(TocEntry(level, id, plain))
                out.append("<h$level id=\"$id\">").append(text).append("</h$level>")
                i++; continue
            }
            if (trimmed.startsWith("----")) { flushPara(); closeLists(); out.append("<hr>"); i++; continue }
            val lm = LIST.matchEntire(trimmed)
            if (lm != null) {
                flushPara()
                val marks = lm.groupValues[1].replace(';', ':')
                var content = lm.groupValues[2]
                // ";term : definition" on one line
                var defTerm: String? = null
                if (lm.groupValues[1].endsWith(";")) {
                    val c = content.indexOf(" : ")
                    if (c > 0) { defTerm = content.substring(0, c); content = content.substring(c + 3) } else { defTerm = content; content = "" }
                }
                var common = 0
                while (common < listStack.length && common < marks.length && listStack[common] == marks[common]) common++
                if (common == marks.length && common == listStack.length && common > 0) {
                    // sibling item
                    when (marks.last()) { '*', '#' -> out.append("</li><li>"); else -> out.append("</dd><dd>") }
                } else {
                    closeLists(common)
                    if (common == marks.length && common > 0) {
                        when (marks.last()) { '*', '#' -> out.append("</li><li>"); else -> out.append("</dd><dd>") }
                    }
                    for (j in common until marks.length) {
                        when (marks[j]) { '*' -> out.append("<ul><li>"); '#' -> out.append("<ol><li>"); else -> out.append("<dl class=\"indent\"><dd>") }
                        listStack.append(marks[j])
                    }
                }
                if (defTerm != null) out.append("<b>").append(inline(defTerm)).append("</b>").append(if (content.isNotBlank()) " — " else "")
                out.append(inline(content))
                i++; continue
            }
            if (trimmed.isEmpty()) { flushPara(); closeLists(); i++; continue }
            if (listStack.isNotEmpty()) closeLists()
            // A line holding only a block placeholder ends the current paragraph
            if (BLOCK_ONLY.matches(trimmed.trim()) && trimmed.trim().startsWith("\u0002")) {
                flushPara(); out.append(trimmed.trim()); i++; continue
            }
            if (para.isNotEmpty()) para.append(' ')
            para.append(trimmed.trimStart())
            i++
        }
        flushPara(); closeLists()
        return out.toString()
    }

    private fun table(src: String): String {
        val lines = src.split('\n')
        val sb = StringBuilder()
        val first = lines.first().trim().removePrefix("{|")
        val isWide = first.contains("wikitable") || first.contains("sortable")
        sb.append(if (isWide) "<div class=\"tablewrap\"><table class=\"wikitable\">" else "<div class=\"tablewrap\"><table>")
        var rowOpen = false
        var cellOpen: String? = null
        val cell = StringBuilder()
        var nestedDepth = 0
        val nested = StringBuilder()
        fun closeCell() {
            val tag = cellOpen ?: return
            val (attrs, content) = splitCellAttrs(cell.toString())
            val body = renderCellContent(content)
            sb.append("<$tag$attrs>").append(body).append("</$tag>")
            cellOpen = null; cell.setLength(0)
        }
        fun openRow() { if (!rowOpen) { sb.append("<tr>"); rowOpen = true } }
        fun closeRow() { closeCell(); if (rowOpen) { sb.append("</tr>"); rowOpen = false } }
        for (idx in 1 until lines.size) {
            val raw = lines[idx]
            val l = raw.trim()
            if (nestedDepth > 0) {
                nested.append(raw).append('\n')
                if (l.startsWith("{|")) nestedDepth++
                if (l.startsWith("|}")) { nestedDepth--; if (nestedDepth == 0) { cell.append('\n').append(protect(table(nested.toString()))); nested.setLength(0) } }
                continue
            }
            when {
                l.startsWith("{|") -> { nestedDepth = 1; nested.setLength(0); nested.append(raw).append('\n') }
                l.startsWith("|}") -> { closeRow(); break }
                l.startsWith("|+") -> { closeCell(); sb.append("<caption>").append(inline(splitCellAttrs(l.substring(2)).second)).append("</caption>") }
                l.startsWith("|-") -> closeRow()
                l.startsWith("!") -> {
                    closeCell(); openRow()
                    val parts = splitCells(l.substring(1), "!!")
                    parts.forEachIndexed { pi, p -> if (pi > 0) closeCell(); cellOpen = "th"; cell.append(p) }
                }
                l.startsWith("|") -> {
                    closeCell(); openRow()
                    val parts = splitCells(l.substring(1), "||")
                    parts.forEachIndexed { pi, p -> if (pi > 0) closeCell(); cellOpen = "td"; cell.append(p) }
                }
                else -> if (cellOpen != null) cell.append('\n').append(raw)
            }
        }
        closeRow()
        sb.append("</table></div>")
        return protect(sb.toString())
    }

    private fun splitCells(s: String, sep: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var last = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if ((c == '[' || c == '{') && i + 1 < s.length && s[i + 1] == c) { depth++; i += 2; continue }
            if ((c == ']' || c == '}') && i + 1 < s.length && s[i + 1] == c) { depth--; i += 2; continue }
            if (depth <= 0 && s.startsWith(sep, i)) { out.add(s.substring(last, i)); i += sep.length; last = i; continue }
            i++
        }
        out.add(s.substring(last))
        return out
    }

    private fun splitCellAttrs(cell: String): Pair<String, String> {
        // "attr=x | content" -> attrs, content (only when the first single pipe precedes any link)
        var depth = 0
        for (i in cell.indices) {
            val c = cell[i]
            if (c == '[' || c == '{') depth++ else if (c == ']' || c == '}') depth--
            else if (c == '|' && depth == 0) {
                val attrs = cell.substring(0, i)
                if (attrs.contains('=') || attrs.isBlank()) {
                    val keep = StringBuilder()
                    Regex("(colspan|rowspan)\\s*=\\s*\"?(\\d+)\"?", RegexOption.IGNORE_CASE).findAll(attrs).forEach {
                        keep.append(' ').append(it.groupValues[1].lowercase()).append("=\"").append(it.groupValues[2]).append('"')
                    }
                    return keep.toString() to cell.substring(i + 1)
                }
                break
            }
            else if (c == '\n') break
        }
        return "" to cell
    }

    private fun renderCellContent(content: String): String {
        val c = content.trim()
        if (c.isEmpty()) return ""
        return if (c.contains('\n')) {
            val inner = renderBlocks(c, ArrayList())
            inner.removePrefix("<p>").removeSuffix("</p>").takeIf { !inner.substring(3).contains("<p>") } ?: inner
        } else inline(c)
    }

    // ---------------------------------------------------------------- inline

    fun inline(src: String): String {
        if (src.isEmpty()) return ""
        var s = links(src)
        s = externalLinks(s)
        s = htmlTags(s)
        s = boldItalic(s)
        return s
    }

    private fun links(s: String): String {
        if (!s.contains("[[")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val st = s.indexOf("[[", i)
            if (st < 0) { sb.append(s, i, s.length); break }
            sb.append(s, i, st)
            val e = WikiScan.matchBrackets(s, st)
            if (e < 0) { sb.append("[["); i = st + 2; continue }
            val inner = s.substring(st + 2, e - 2)
            // link trail: letters directly after ]]
            var te = e
            while (te < s.length && s[te].isLetter() && s[te].code < 0x250) te++
            val trail = s.substring(e, te)
            val (html, usedTrail) = link(inner, trail)
            sb.append(html)
            i = if (usedTrail) te else e
        }
        return sb.toString()
    }

    private val fileNs = setOf("file", "image", "datei", "fichier", "imagen", "media")
    private val dropNs = setOf("category", "kategorie")

    private fun link(inner: String, trail: String): Pair<String, Boolean> {
        val parts = WikiScan.splitPipes(inner)
        var target = parts[0].trim()
        val leadingColon = target.startsWith(":")
        if (leadingColon) target = target.substring(1)
        val colon = target.indexOf(':')
        val ns = if (colon > 0) target.substring(0, colon).trim().lowercase() else ""
        if (!leadingColon && ns in fileNs) return figureFromLink(target.substring(colon + 1), parts.drop(1)) to false
        if (!leadingColon && ns in dropNs) return "" to false
        if (!leadingColon && colon in 2..3 && ns.all { it in 'a'..'z' || it == '-' } && parts.size == 1 && ns !in setOf("wp", "w")) {
            return "" to false // interlanguage link
        }
        var label = if (parts.size > 1) parts.drop(1).joinToString("|") else target.removePrefix(":")
        if (parts.size > 1 && label.isBlank()) label = target.substringAfter(':').replace(Regex("\\s*\\(.*\\)$"), "") // pipe trick
        val sisterNs = setOf("wikt", "wiktionary", "s", "wikisource", "q", "wikiquote", "b", "wikibooks", "voy", "wikivoyage", "commons", "c", "species", "d", "wikidata", "meta", "m", "n", "wikinews", "v", "mw", "wmf", "foundation", "oldid", "diff", "special", "user", "wikipedia", "help", "template", "portal", "talk", "draft", "module")
        val labelHtml = inline(label) + trail
        if (ns in sisterNs || ns in dropNs || ns in fileNs) return "<span class=\"xlink\">$labelHtml</span>" to true
        if (target.startsWith("#")) {
            val anchor = "s-" + target.substring(1).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            return "<a href=\"#$anchor\">$labelHtml</a>" to true
        }
        val canonical = TextKey.canonicalTitle(target.removePrefix("w:").removePrefix("en:"))
        if (canonical.isEmpty()) return labelHtml to true
        val frag = target.substringAfter('#', "").let { if (it.isNotEmpty()) "#s-" + it.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') else "" }
        return "<a href=\"$linkPrefix${urlEnc(canonical)}$frag\">$labelHtml</a>" to true
    }

    private val imageOptions = Regex(
        "^(thumb|thumbnail|frame|framed|frameless|border|left|right|center|centre|none|baseline|middle|sub|super|text-top|text-bottom|top|bottom|" +
            "upright(=.*)?|upright ?[0-9.]+|\\d*x?\\d+ ?px|alt=.*|link=.*|page=.*|class=.*|lang=.*|thumbtime=.*|start=.*|end=.*|loop|muted|" +
            "uploadpage=.*|[0-9]+px|left ?.*px)$", RegexOption.IGNORE_CASE
    )

    private fun figureFromLink(name: String, opts: List<String>): String {
        val caption = opts.lastOrNull { !imageOptions.matches(it.trim()) }?.trim()
        val isThumb = opts.any { it.trim().lowercase().let { o -> o == "thumb" || o == "thumbnail" || o == "frame" || o == "framed" } }
        val cls = when {
            !isThumb && opts.any { it.trim().matches(Regex("\\d{1,2}px")) } -> "icon"
            isThumb -> "thumb"
            else -> "plain"
        }
        if (cls == "icon") return ""
        val fig = figure(name.trim(), if (caption != null) inline(expandTemplates(caption)) else "", cls)
        return if (cls == "thumb") protectBlock(fig) else protect(fig)
    }

    private fun figure(name: String, captionHtml: String, cls: String): String {
        val clean = name.trim().replace(' ', '_')
        if (clean.isEmpty()) return ""
        val lower = clean.lowercase()
        if (lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".ogv") || lower.endsWith(".webm") ||
            lower.endsWith(".mid") || lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac") ||
            lower.endsWith(".pdf") || lower.endsWith(".djvu") || lower.endsWith(".stl")) return ""
        images.add(clean)
        val alt = esc(restore(captionHtml).replace(TAGS, "").take(160))
        return "<figure class=\"$cls\"><img loading=\"lazy\" src=\"$imagePrefix${urlEnc(clean)}\" alt=\"$alt\" onerror=\"arkImgFail(this)\">" +
            (if (captionHtml.isNotBlank()) "<figcaption>$captionHtml</figcaption>" else "") + "</figure>"
    }

    private fun externalLinks(s: String): String {
        if (!s.contains('[')) return s
        return EXTLINK.replace(s) { m ->
            val url = m.groupValues[1]
            val label = m.groupValues[2].trim()
            if (label.isEmpty()) "" else "<a class=\"ext\" href=\"${esc(url)}\">$label</a>"
        }
    }

    private val allowedTags = setOf(
        "b", "i", "u", "s", "em", "strong", "sup", "sub", "small", "big", "code", "kbd", "var", "samp", "tt", "del", "ins", "br",
        "blockquote", "span", "div", "center", "abbr", "q", "cite", "mark", "dfn", "bdi", "wbr", "hr", "p", "ul", "ol", "li", "dl", "dt", "dd",
        "font", "strike", "time", "data", "ruby", "rt", "rp", "table", "tr", "td", "th", "tbody", "thead", "caption"
    )

    private fun htmlTags(s: String): String {
        if (!s.contains('<')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '<') {
                val m = TAG.matchAt(s, i)
                if (m != null) {
                    val closing = m.groupValues[1] == "/"
                    val name = m.groupValues[2].lowercase()
                    val attrs = m.groupValues[3]
                    if (name in allowedTags || name == "figure" || name == "figcaption" || name == "img" || name == "a" || name == "aside") {
                        // Our own generated markup (figure/img/a) passes through untouched.
                        if (name == "figure" || name == "figcaption" || name == "img" || name == "a" || name == "aside") sb.append(m.value)
                        else if (name == "center") sb.append(if (closing) "</div>" else "<div class=\"center\">")
                        else if (name == "font") sb.append(if (closing) "</span>" else "<span>")
                        else {
                            val keep = Regex("(colspan|rowspan)\\s*=\\s*\"?(\\d+)\"?", RegexOption.IGNORE_CASE).findAll(attrs)
                                .joinToString("") { " " + it.groupValues[1].lowercase() + "=\"" + it.groupValues[2] + "\"" }
                            val selfClose = if (name == "br" || name == "hr" || name == "wbr") "" else ""
                            sb.append('<').append(if (closing) "/" else "").append(name).append(keep).append(selfClose).append('>')
                        }
                    }
                    // unknown tags are dropped, their content kept
                    i += m.value.length
                    continue
                }
                sb.append("&lt;"); i++; continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun boldItalic(s: String): String {
        if (!s.contains("''")) return s
        return s.split('\n').joinToString("\n") { boldItalicLine(it) }
    }

    private fun boldItalicLine(line: String): String {
        if (!line.contains("''")) return line
        val sb = StringBuilder(line.length + 16)
        var bold = false; var italic = false
        var i = 0
        while (i < line.length) {
            if (line[i] == '\'' && i + 1 < line.length && line[i + 1] == '\'') {
                var n = 0
                while (i + n < line.length && line[i + n] == '\'') n++
                var run = n
                if (run == 4) { sb.append('\''); run = 3 }
                if (run > 5) { sb.append("'".repeat(run - 5)); run = 5 }
                when (run) {
                    2 -> { sb.append(if (italic) "</i>" else "<i>"); italic = !italic }
                    3 -> { sb.append(if (bold) "</b>" else "<b>"); bold = !bold }
                    5 -> {
                        if (bold && italic) { sb.append("</i></b>"); bold = false; italic = false }
                        else if (bold) { sb.append("<i>"); italic = true }
                        else if (italic) { sb.append("<b>"); bold = true }
                        else { sb.append("<b><i>"); bold = true; italic = true }
                    }
                }
                i += n
                continue
            }
            sb.append(line[i]); i++
        }
        if (italic) sb.append("</i>")
        if (bold) sb.append("</b>")
        return sb.toString()
    }

    companion object {
        private val PH = Regex("\u0002(\\d+)\u0002")
        private val COMMENT = Regex("<!--.*?(-->|$)", RegexOption.DOT_MATCHES_ALL)
        private val NOWIKI = Regex("<nowiki\\s*>(.*?)</nowiki\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val PRE = Regex("<pre(\\s[^>]*)?>(.*?)</pre\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val SYNTAX = Regex("<(?:syntaxhighlight|source)(\\s[^>]*)?>(.*?)</(?:syntaxhighlight|source)\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val MATH = Regex("<math(\\s[^>]*)?>(.*?)</math\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val CHEM = Regex("<(?:chem|ce)(\\s[^>]*)?>(.*?)</(?:chem|ce)\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val REF_SELF = Regex("<ref(\\s[^>]*)?/\\s*>", RegexOption.IGNORE_CASE)
        private val REF_BLOCK = Regex("<ref(\\s[^>]*)?>.*?</ref\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val REFERENCES = Regex("<references(\\s[^>]*)?/\\s*>", RegexOption.IGNORE_CASE)
        private val REFERENCES_BLOCK = Regex("<references(\\s[^>]*)?>.*?</references\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val DROP_TAGS = Regex("<(timeline|score|graph|mapframe|maplink|templatedata|templatestyles|indicator|charinsert|inputbox|categorytree|hiero)(\\s[^>]*)?(/>|>.*?</\\1\\s*>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val GALLERY = Regex("<gallery(\\s[^>]*)?>(.*?)</gallery\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val IMAGEMAP = Regex("<imagemap(\\s[^>]*)?>(.*?)</imagemap\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val POEM = Regex("<poem(\\s[^>]*)?>(.*?)</poem\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val MAGIC = Regex("__[A-Z]+__")
        private val HEADING = Regex("^(={2,6})\\s*(.+?)\\s*\\1\\s*$")
        private val LIST = Regex("^([*#:;]+)\\s*(.*)$")
        private val EXTLINK = Regex("(?<!\\[)\\[((?:https?:)?//[^\\s\\]]+)(?:\\s+([^\\]]*))?\\]")
        private val TAG = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\\s[^<>]*)?)\\s*/?>")
        private val TAGS = Regex("<[^>]+>")
        private val BLOCK_ONLY = Regex("^(\\s*\u0002\\d+\u0002\\s*)+$")
        private val EMPTY_PARENS = Regex("\\s?\\((\\s|&nbsp;|[;,:]|or|\\s)*\\)")

        fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
