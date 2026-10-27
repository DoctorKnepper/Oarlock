"""Generates app/src/main/assets/catalog.json.

Inputs: Kiwix library_zim.xml (sizes + current URLs as fallbacks), the books.json produced by books.py.
Map sizes come from measuring the Protomaps planet directory (see pmx.py).
"""
import json, os, sys, xml.etree.ElementTree as ET

LIB, BOOKS, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
lib = {}
for b in ET.parse(LIB).getroot().findall('book'):
    key = (b.get('name'), b.get('flavour') or '')
    prev = lib.get(key)
    if prev is None or b.get('date') > prev.get('date'):
        lib[key] = b

def zim(pid, name, flavour, title, desc, cat, images=False):
    b = lib.get((name, flavour))
    if b is None:
        raise SystemExit(f"missing {name} {flavour}")
    url = b.get('url', '').replace('.meta4', '')
    return {"id": pid, "kind": "zim", "title": title, "desc": desc, "category": cat,
            "size": int(b.get('size')) * 1024, "zimName": name, "flavour": flavour, "url": url,
            "date": b.get('date'), "articles": int(b.get('articleCount') or 0), "images": images}

packs = []
P = packs.append

# --- Medical
P(zim("wikem", "wikem_en_all", "maxi", "WikEM Emergency Medicine", "Concise emergency-medicine reference used by clinicians: conditions, treatments, procedures, doses.", "Medical"))
P(zim("wikimed", "wikipedia_en_medicine", "maxi", "WikiMed Medical Encyclopedia", "Every medical article on Wikipedia — anatomy, diseases, drugs, first aid — with images.", "Medical", True))
P(zim("zim-medicine", "zimgit-medicine_en", "", "Medical Library", "Field medicine manuals and guides collected for emergencies (PDFs).", "Medical"))
P(zim("military-medicine", "irp.fas.org_en_military-medicine", "", "Military Medicine", "U.S. military medical manuals: trauma care, field surgery, preventive medicine.", "Medical"))
P(zim("nhs-medicines", "nhs.uk_en_medicines", "", "NHS Medicines A to Z", "Plain-language guide to common medicines, doses and side effects from the UK NHS.", "Medical"))
P(zim("medicine-libretexts", "libretexts.org_en_med", "", "Medicine LibreTexts", "Open medical and nursing textbooks.", "Medical"))

# --- Survival & preparedness
P(zim("post-disaster", "zimgit-post-disaster_en", "", "Post-Disaster Resource Library", "Curated manuals on shelter, water, sanitation, power, food and rebuilding after a disaster.", "Survival"))
P(zim("water", "zimgit-water_en", "", "Water Treatment Library", "How to find, purify and store safe drinking water.", "Survival"))
P(zim("food-prep", "zimgit-food-preparation_en", "", "Food for Preppers", "Food storage, preservation and preparation guides.", "Survival"))
P(zim("knots-lib", "zimgit-knots_en", "", "A Library of Knots", "Illustrated guides to useful knots and rope work.", "Survival"))
P(zim("knots-wiki", "wikipedia_en_knots", "maxi", "Knots (Wikipedia)", "Every knot article on Wikipedia, with diagrams.", "Survival", True))
P(zim("armypubs", "armypubs_en_all", "", "U.S. Army Publications", "Field manuals and technical manuals from the Army Publishing Directorate, including survival, first aid and engineering.", "Survival"))
P(zim("trueprepper", "trueprepper.com_en_all", "", "True Prepper", "Preparedness articles: food storage, bug-out planning, power outages.", "Survival"))
P(zim("outdoors-qa", "outdoors.stackexchange.com_en_all", "", "The Great Outdoors Q&A", "Questions and answers on hiking, camping and wilderness skills.", "Survival"))
P(zim("sustainability-qa", "sustainability.stackexchange.com_en_all", "", "Sustainable Living Q&A", "Practical answers on low-impact, self-reliant living.", "Survival"))

# --- Repair & skills
P(zim("ifixit", "ifixit_en_all", "", "iFixit Repair Guides", "Step-by-step repair guides with photos for electronics, vehicles, appliances and tools.", "Repair & Skills"))
P(zim("appropedia", "appropedia_en_all", "maxi", "Appropedia", "Appropriate technology: water, energy, building, farming and low-tech solutions.", "Repair & Skills"))
P(zim("energypedia", "energypedia_en_all", "maxi", "energypedia", "Off-grid and renewable energy know-how: solar, biogas, cooking stoves, micro-hydro.", "Repair & Skills"))
P(zim("diy-qa", "diy.stackexchange.com_en_all", "", "Home Improvement Q&A", "Plumbing, wiring, carpentry and home repair answers.", "Repair & Skills"))
P(zim("mechanics-qa", "mechanics.stackexchange.com_en_all", "", "Motor Vehicle Mechanics Q&A", "Car and engine repair answers.", "Repair & Skills"))
P(zim("gardening-qa", "gardening.stackexchange.com_en_all", "", "Gardening & Landscaping Q&A", "Growing food and plants, soil, pests.", "Repair & Skills"))
P(zim("cooking-qa", "cooking.stackexchange.com_en_all", "", "Cooking Q&A", "Practical cooking and food-safety answers.", "Repair & Skills"))
P(zim("electronics-qa", "electronics.stackexchange.com_en_all", "", "Electronics Q&A", "Circuits, power supplies, batteries and repair.", "Repair & Skills"))
P(zim("ham-qa", "ham.stackexchange.com_en_all", "", "Amateur Radio Q&A", "Ham radio equipment, antennas and emergency communication.", "Repair & Skills"))
P(zim("wikibooks", "wikibooks_en_all", "nopic", "Wikibooks", "Open textbooks and how-to manuals, including cookbooks and repair guides.", "Repair & Skills"))

# --- Encyclopedias & reference
P({"id": "wikipedia", "kind": "wikipedia", "title": "Wikipedia (English, full text)",
   "desc": "All 7 million English Wikipedia articles as text, straight from Wikimedia's official dump. Opens one article at a time without unpacking the rest. Optional Optimize step shrinks it further.",
   "category": "Encyclopedias", "size": 26917225724 + 285227194, "dumpUrl": "https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-pages-articles-multistream.xml.bz2",
   "indexUrl": "https://dumps.wikimedia.org/enwiki/latest/enwiki-latest-pages-articles-multistream-index.txt.bz2", "indexSize": 285227194, "extraSpace": 700_000_000})
P({"id": "simplewiki", "kind": "wikipedia", "title": "Simple English Wikipedia",
   "desc": "About 270,000 articles written in plain English. A light alternative or companion to the full Wikipedia.",
   "category": "Encyclopedias", "size": 387919857 + 10_000_000, "dumpUrl": "https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles-multistream.xml.bz2",
   "indexUrl": "https://dumps.wikimedia.org/simplewiki/latest/simplewiki-latest-pages-articles-multistream-index.txt.bz2", "indexSize": 10_000_000, "extraSpace": 20_000_000})
P(zim("wiki-top", "wikipedia_en_top", "maxi", "Best of Wikipedia (with images)", "The ~50,000 most-read Wikipedia articles with pictures. Its images also appear in the full-text Wikipedia.", "Encyclopedias", True))
for (pid, name, title) in [("wiki-chemistry", "wikipedia_en_chemistry", "Chemistry"), ("wiki-physics", "wikipedia_en_physics", "Physics"),
                           ("wiki-mathematics", "wikipedia_en_mathematics", "Mathematics"), ("wiki-geography", "wikipedia_en_geography", "Geography"),
                           ("wiki-history", "wikipedia_en_history", "History"), ("wiki-molcell", "wikipedia_en_molcell", "Molecular & Cell Biology"),
                           ("wiki-astronomy", "wikipedia_en_astronomy", "Astronomy"), ("wiki-computer", "wikipedia_en_computer", "Computing"),
                           ("wiki-climate", "wikipedia_en_climate-change", "Climate")]:
    P(zim(pid, name, "maxi", f"{title} by Wikipedia (with images)", f"Wikipedia's {title.lower()} articles with images. Also supplies pictures to matching full-text Wikipedia articles.", "Image Packs", True))
P(zim("wiktionary", "wiktionary_en_all", "nopic", "Wiktionary Dictionary", "English dictionary and thesaurus with definitions, etymology and translations.", "Encyclopedias"))
P(zim("wiktionary-simple", "wiktionary_en-simple_all", "nopic", "Simple English Wiktionary", "Compact plain-English dictionary.", "Encyclopedias"))
P(zim("wikivoyage", "wikivoyage_en_all", "nopic", "Wikivoyage Travel Guides", "Travel guides for every country and city: getting around, where to stay, safety.", "Encyclopedias"))
P(zim("wikisource", "wikisource_en_all", "nopic", "Wikisource Library", "Hundreds of thousands of public-domain texts: historical documents, laws, speeches, scripture translations, literature.", "Books"))
P(zim("iep", "internet-encyclopedia-philosophy_en_all", "", "Internet Encyclopedia of Philosophy", "Peer-reviewed articles on philosophers and philosophical ideas.", "Encyclopedias"))

# --- Education
for (pid, name, title) in [("lt-biology", "libretexts.org_en_bio", "Biology"), ("lt-chemistry", "libretexts.org_en_chem", "Chemistry"),
                           ("lt-physics", "libretexts.org_en_phys", "Physics"), ("lt-math", "libretexts.org_en_math", "Mathematics"),
                           ("lt-engineering", "libretexts.org_en_eng", "Engineering"), ("lt-geosciences", "libretexts.org_en_geo", "Geosciences"),
                           ("lt-k12", "libretexts.org_en_k12", "K-12 Education")]:
    P(zim(pid, name, "", f"{title} LibreTexts", f"Open {title.lower()} textbooks.", "Education"))
P(zim("phet", "phet_en_all", "", "PhET Science Simulations", "Interactive math and science simulations.", "Education"))

# --- Books
books = json.load(open(BOOKS))
for c in books:
    n = len(c['items'])
    ia = sum(1 for i in c['items'] if i['src'].startswith('ia:'))
    known = json.load(open(os.path.join(os.path.dirname(__file__), 'book_sizes.json'))) if os.path.exists(os.path.join(os.path.dirname(__file__), 'book_sizes.json')) else {}
    litef = os.path.join(os.path.dirname(__file__), 'book_lite.json')
    lite = json.load(open(litef)) if os.path.exists(litef) else {}
    for it in c['items']:
        if it['src'] in lite: it['lite'] = True  # very large illustrated edition: download the text-only EPUB instead
    size = sum(lite.get(it['src']) or known.get(it['src'], 3_000_000) for it in c['items'])
    P({"id": c['id'], "kind": "books", "title": c['name'], "desc": c['desc'] + f" {n} books.", "category": "Books",
       "size": size, "items": c['items']})
P({"id": "gutenberg-catalog", "kind": "gutenberg", "title": "Project Gutenberg Catalog",
   "desc": "Browse and search all 70,000+ English public-domain books by genre, then download any of them while you're online.",
   "category": "Books", "size": 30_000_000, "url": "https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv.gz"})
for (pid, name, title) in [("pg-philosophy", "gutenberg_en_lcc-b", "Philosophy, Psychology & Religion"), ("pg-science", "gutenberg_en_lcc-q", "Science"),
                           ("pg-medicine", "gutenberg_en_lcc-r", "Medicine"), ("pg-agriculture", "gutenberg_en_lcc-s", "Agriculture"),
                           ("pg-technology", "gutenberg_en_lcc-t", "Technology"), ("pg-military", "gutenberg_en_lcc-u", "Military Science"),
                           ("pg-literature", "gutenberg_en_lcc-pr", "English Literature")]:
    P(zim(pid, name, "", f"Gutenberg Shelf: {title}", f"Every English Project Gutenberg book in the {title.lower()} section of the Library of Congress classification.", "Books"))

# --- Maps (sizes measured from the Protomaps planet build, Oct 2026)
NA = [[-170.0, 14.0, -50.0, 72.0], [-141.0, 72.0, -60.0, 84.0]]
P({"id": "map-world", "kind": "map", "title": "World Overview Map", "desc": "Countries, cities, major roads and water for the whole planet, down to regional detail (zoom 7).",
   "category": "Maps", "size": 190_000_000, "areas": [[-180.0, -85.0, 180.0, 85.0]], "minZoom": 0, "maxZoom": 7})
P({"id": "map-na-12", "kind": "map", "title": "North America Road Map", "desc": "United States, Canada and Mexico with highways, main roads, towns and terrain features (zoom 12). Best value for navigation between towns.",
   "category": "Maps", "size": 4_100_000_000, "areas": NA, "minZoom": 0, "maxZoom": 12, "group": "na"})
P({"id": "map-na-13", "kind": "map", "title": "North America Street Map", "desc": "United States, Canada and Mexico with most streets named (zoom 13).",
   "category": "Maps", "size": 8_500_000_000, "areas": NA, "minZoom": 0, "maxZoom": 13, "group": "na"})
P({"id": "map-na-14", "kind": "map", "title": "North America Full Detail", "desc": "United States, Canada and Mexico with every street, path and building footprint (zoom 14).",
   "category": "Maps", "size": 16_700_000_000, "areas": NA, "minZoom": 0, "maxZoom": 14, "group": "na"})

defcon = ["wikem", "zim-medicine", "water", "knots-lib", "food-prep", "military-medicine", "map-world", "wikimed", "post-disaster",
          "map-na-12", "books-sacred", "books-skills", "simplewiki", "wikipedia", "ifixit", "appropedia", "wikivoyage", "armypubs"]
ids = {p['id'] for p in packs}
for d in defcon:
    assert d in ids, d
cats = ["Medical", "Survival", "Maps", "Encyclopedias", "Image Packs", "Repair & Skills", "Books", "Education"]
json.dump({"version": 1, "generated": "2026-10-27", "categories": cats, "defcon": defcon, "packs": packs}, open(OUT, 'w'), ensure_ascii=False, indent=1)
tot = sum(p['size'] for p in packs if p['id'] in defcon)
print(len(packs), "packs; default DEFCON set", round(tot / 1e9, 1), "GB")
