"""Builds the curated public-domain book collections for Ark's catalog.

Gutenberg ids are checked against Project Gutenberg's pg_catalog.csv so titles and authors
come from the source; archive.org items are listed explicitly.
"""
import csv, json, re, sys

CAT = sys.argv[1]
rows = {r['Text#']: r for r in csv.DictReader(open(CAT, encoding='utf-8'))}

FIX = {"Edwin, Sir Arnold": "Edwin Arnold", "Joseph, Jr. Smith": "Joseph Smith", "Leo, graf Tolstoy": "Leo Tolstoy",
       "Charles, Sir Lyell": "Charles Lyell", "Emperor of Rome Marcus Aurelius": "Marcus Aurelius",
       "active 6th century B.C. Sunzi": "Sun Tzu", "Mary Wollstonecraft Shelley": "Mary Shelley",
       "-1518 Kabir": "Kabir", "Alfred Tennyson, Baron Tennyson": "Alfred Tennyson", "Charlotte, Lady Schreiber": "Lady Charlotte Guest",
       "E. A. Wallis , Sir Budge": "E. A. Wallis Budge", "Emmuska Orczy, Baroness Orczy": "Baroness Orczy",
       "Ernest Henry, Sir Shackleton": "Ernest Shackleton", "of Hippo, Saint Augustine": "Saint Augustine", "à Kempis Thomas": "Thomas à Kempis"}

def author(a):
    n = _author(a)
    return FIX.get(n, n)

def _author(a):
    first = a.split(';')[0]
    first = re.sub(r',\s*\d.*$', '', first).strip()
    first = re.sub(r'\s*\[.*?\]', '', first)
    if ',' in first:
        last, rest = first.split(',', 1)
        rest = re.sub(r'\(.*?\)', '', rest).strip()
        return (rest + ' ' + last).strip()
    return first

COLLECTIONS = [
  ("sacred", "Sacred & Spiritual Texts", "Scriptures of the world's ten largest religions and other traditions, in public-domain English translations.", [
    ("Christianity", [10, 8300, 124, 1653, 3296, 131]),
    ("Islam", [2800, 16955]),
    ("Hinduism", [2388, 3283]),
    ("Buddhism", [2017, 35895]),
    ("Sikhism", ["ia:thesikhreligion01macauoft|The Sikh Religion, Vol. 1 (Macauliffe, 1909)|Max Arthur Macauliffe",
                 "ia:thesikhreligioni02macauoft|The Sikh Religion, Vol. 2 (Macauliffe, 1909)|Max Arthur Macauliffe",
                 "ia:thesikhreligioni03macauoft|The Sikh Religion, Vol. 3 (Macauliffe, 1909)|Max Arthur Macauliffe",
                 "ia:thesikhreligioni05macauoft|The Sikh Religion, Vol. 5 (Macauliffe, 1909)|Max Arthur Macauliffe",
                 "ia:thesikhreligioni06macauoft|The Sikh Religion, Vol. 6 (Macauliffe, 1909)|Max Arthur Macauliffe"]),
    ("Judaism", ["ia:holyscripturesac028077mbp|The Holy Scriptures according to the Masoretic Text (JPS, 1917)|Jewish Publication Society",
                 8547, 14368]),
    ("Taoism & Confucianism", [216, 3330, 59709]),
    ("Baháʼí Faith", [16941, 17310, 19312]),
    ("Jainism", ["ia:jainasutrasparti029233mbp|Jaina Sutras, Part 1 (Sacred Books of the East 22)|Hermann Jacobi",
                 "ia:mlbd.gainasutraspart20000vol-45.unse|Jaina Sutras, Part 2 (Sacred Books of the East 45)|Hermann Jacobi"]),
    ("Shinto", [46038]),
    ("Zoroastrianism", ["ia:zendavesta02darm|The Zend-Avesta, Part 2 (Sacred Books of the East 23)|James Darmesteter",
                        "ia:zendavesta03darm|The Zend-Avesta, Part 3: Yasna & Gathas (Sacred Books of the East 31)|L. H. Mills"]),
    ("Latter Day Saints", [17]),
    ("Mysticism & Devotion", [6519, 7164, 58585]),
    ("Ancient & Indigenous", [7145, 56550, 340]),
    ("Study of Religion", [621, 3623]),
  ]),
  ("uu", "Unitarian Universalist Heritage", "Transcendentalist, Unitarian and Universalist writings that shaped Unitarian Universalism.", [
    ("Transcendentalism", [2944, 2945, 205, 8642]),
    ("Unitarian & Universalist", [11142, 18578, 8605, 34573, 8225, 44736]),
  ]),
  ("science", "Foundations of Science", "Landmark works of mathematics, physics, astronomy, chemistry, biology, medicine and geology.", [
    ("Mathematics", [21076, 33283]),
    ("Physics & Astronomy", [76404, 33504, 46036, 30155, 14725, 33810, 14986, 69914]),
    ("Chemistry", [30775, 22914, 14474]),
    ("Life Sciences", [1228, 2300, 944, 67065, 15491, 67000]),
    ("Medicine", [72583, 12439]),
    ("Earth Sciences", [33224]),
  ]),
  ("philosophy", "Foundations of Philosophy", "Ancient to modern philosophy, ethics and political thought.", [
    ("Ancient", [1497, 1656, 1600, 8438, 6762, 785, 2680, 10661]),
    ("Modern", [59, 70091, 3800, 3207, 7370, 9662, 4280, 5682, 4363, 1998]),
    ("Political & Social", [34901, 11224, 46333, 3300, 1232, 147, 3742, 1404, 3420, 71]),
    ("Essays & Wisdom", [575, 3600, 14328, 18269, 5116, 132]),
  ]),
  ("mind", "Mind, Society & Economics", "Founding works of psychology, sociology, economics and civil rights.", [
    ("Psychology", [66048, 57628, 57634, 37423]),
    ("Society & Rights", [815, 816, 27083, 408]),
    ("Economics", [833, 55308, 61]),
  ]),
  ("skills", "Practical Skills & Self-Reliance", "Older public-domain guides to woodcraft, cooking, food preservation, gardening, farming, herbs and radio. Historic advice: check anything safety-critical against current guidance.", [
    ("Outdoors & Woodcraft", ["q:boy scouts handbook", 34607, 44215, "q:shelters, shacks", 54138, 9198]),
    ("Knots & Rope", ["q:knots, splices"]),
    ("Food & Preserving", [8542, "q:boston cooking-school cook book", 66136, 63056, 10136]),
    ("Garden & Farm", ["q:vegetable garden", 39791, 25185]),
    ("Herbs & Wild Plants", [49513, 3003, 30734]),
    ("Building, Tools & Crafts", ["q:carpentry for boys", 21724]),
    ("Radio", [6934]),
  ]),
  ("classics", "Classic Novels", "The great novels of world literature.", [
    ("Novels", [1342, 2701, 135, 2600, 2554, 84, 98, 76, 1400, 1260, 1184, 1399, 28054, 145, 996]),
  ]),
  ("poetry", "Poetry & Drama", "Major poets and playwrights, from Sophocles and Shakespeare to Dickinson and Frost.", [
    ("Epic & Narrative Poetry", [26, 8800, 2383, 610, 19]),
    ("Poetry", [1322, 12242, 1934, 8209, 9622, 246, 10031, 3026, 1280, 1041]),
    ("Drama", [100, 31, 14591, 2542, 844, 1254]),
  ]),
  ("genre", "Adventure, Mystery & Science Fiction", "Detective stories, early science fiction, gothic horror and swashbuckling adventure.", [
    ("Mystery & Detective", [1661, 2852, 155, 863, 69087, 558]),
    ("Science Fiction", [35, 36, 5230, 164, 18857, 62, 201, 32]),
    ("Gothic & Horror", [345, 43, 209, 10007, 174, 68283, 175]),
    ("Adventure", [1257, 215, 910, 421, 82, 940, 60, 2166, 1965]),
  ]),
  ("myth", "Myth, Epic & Folklore", "The world's foundational stories.", [
    ("Epics", [2199, 1727, 16328, 5186, 5160, "q:gilgamesh", "q:poetic edda", "q:ramayan"]),
    ("Myth & Folk Tales", [4928, 2591, 1597, 21, "q:arabian nights' entertainments"]),
  ]),
  ("history", "History & Civics", "Founding documents and the great histories.", [
    ("Founding Documents", [1, 2, 5, 1404]),
    ("Histories", [2707, 7142, 674, 731]),
  ]),
  ("lives", "Lives, Journeys & Nature", "Memoirs, exploration and nature writing.", [
    ("Memoir & Biography", [23, 11030, 2376, 2397, 20203, 4367]),
    ("Travel & Exploration", [8419, 2055, 3177, 3176, 5199, 11579]),
    ("Nature Writing", [32540, 1408, 42500]),
  ]),
  ("children", "Children's Classics", "Stories for younger readers.", [
    ("Stories", [11, 16, 55, 120, 236, 3836, 521, 45, 514, 113]),
  ]),
]

def find(q):
    rx = re.compile(re.escape(q), re.I)
    hits = [r for r in rows.values() if r['Language'] == 'en' and r['Type'] == 'Text' and rx.search(r['Title'])]
    hits.sort(key=lambda r: int(r['Text#']))
    return hits[0] if hits else None

out = []
for cid, name, desc, groups in COLLECTIONS:
    items = []
    for genre, ids in groups:
        for i in ids:
            if isinstance(i, str) and i.startswith('ia:'):
                ident, title, au = i[3:].split('|')
                items.append({"src": "ia:" + ident, "t": title, "a": au, "g": genre})
                continue
            r = find(i[2:]) if isinstance(i, str) else rows.get(str(i))
            if r is None:
                print('MISSING', cid, i, file=sys.stderr); continue
            title = re.sub(r'\s+', ' ', r['Title']).strip()
            items.append({"src": "pg:" + r['Text#'], "t": title[:120], "a": author(r['Authors']), "g": genre})
    out.append({"id": "books-" + cid, "name": name, "desc": desc, "items": items})

json.dump(out, open(sys.argv[2], 'w'), ensure_ascii=False, indent=1)
for c in out:
    print('##', c['name'], len(c['items']), file=sys.stderr)
    for it in c['items']: print('   ', it['src'], '|', it['t'][:70], '|', it['a'], file=sys.stderr)
