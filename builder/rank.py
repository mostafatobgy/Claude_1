"""Rule-based editorial desk: classify, group duplicate coverage, and score importance (no AI, no cost)."""
import math
import re
import time
from dataclasses import dataclass, field

from fetcher import RawItem

# --- keyword lists (English + Arabic). Weight = how much the word raises importance. -------------
ECONOMY = {
    # high impact for an Egyptian reader
    "central bank": 3, "interest rate": 3, "rate cut": 3, "rate hike": 3, "devaluation": 3, "imf": 3,
    "inflation": 2.5, "pound": 2, "dollar": 1.5, "fuel price": 3, "subsid": 2.5, "debt": 2, "bailout": 3,
    "recession": 2.5, "oil price": 2.5, "opec": 2, "suez canal": 3, "gdp": 2, "budget": 2, "tariff": 2,
    "fed": 2, "federal reserve": 2.5, "stock": 1, "market": 1, "economy": 1, "economic": 1, "bank": 1,
    "investment": 1, "export": 1, "import": 1, "trade": 1, "gold": 1.5, "wheat": 2, "gas": 1.5, "price": 1,
    "البنك المركزي": 3, "الفائدة": 3, "تعويم": 3, "صندوق النقد": 3, "التضخم": 2.5, "الجنيه": 2,
    "الدولار": 1.5, "أسعار الوقود": 3, "البنزين": 2.5, "الدعم": 2, "الديون": 2, "الدين": 1.5, "ركود": 2.5,
    "أسعار النفط": 2.5, "أوبك": 2, "قناة السويس": 3, "الموازنة": 2, "الناتج المحلي": 2, "رسوم جمركية": 2,
    "الاحتياطي الفيدرالي": 2.5, "البورصة": 1, "الأسواق": 1, "الاقتصاد": 1, "اقتصادي": 1, "استثمار": 1,
    "صادرات": 1, "واردات": 1, "التجارة": 1, "الذهب": 1.5, "القمح": 2, "الغاز": 1.5, "أسعار": 1, "البنوك": 1,
}
POLITICS = {
    "war": 3, "ceasefire": 3, "invasion": 3, "airstrike": 2.5, "missile": 2.5, "attack": 2, "killed": 2,
    "coup": 3, "sanction": 2, "election": 2, "president": 1.5, "prime minister": 1.5, "minister": 1,
    "parliament": 1.5, "summit": 2, "agreement": 1.5, "talks": 1.5, "nuclear": 2.5, "gaza": 2.5, "israel": 2,
    "iran": 2, "sudan": 2, "libya": 1.5, "ethiopia": 2, "nile": 2.5, "dam": 1.5, "government": 1,
    "military": 1.5, "protest": 1.5, "un": 1, "security council": 2, "nato": 1.5, "diplomat": 1,
    "حرب": 3, "وقف إطلاق النار": 3, "الهدنة": 3, "غزو": 3, "غارة": 2.5, "غارات": 2.5, "صاروخ": 2.5, "هجوم": 2,
    "قتلى": 2, "مقتل": 2, "انقلاب": 3, "عقوبات": 2, "انتخابات": 2, "الرئيس": 1.5, "رئيس الوزراء": 1.5,
    "وزير": 1, "البرلمان": 1.5, "مجلس النواب": 1.5, "قمة": 2, "اتفاق": 1.5, "مفاوضات": 1.5, "محادثات": 1.5,
    "نووي": 2.5, "غزة": 2.5, "إسرائيل": 2, "إيران": 2, "السودان": 2, "ليبيا": 1.5, "إثيوبيا": 2, "النيل": 2.5,
    "سد النهضة": 3, "الحكومة": 1, "الجيش": 1.5, "احتجاج": 1.5, "مجلس الأمن": 2, "الأمم المتحدة": 1, "الناتو": 1.5,
}
TECH = {
    "artificial intelligence": 2.5, "ai": 2, "openai": 2, "chatgpt": 2, "anthropic": 2, "gemini": 1.5, "nvidia": 2,
    "chip": 2, "semiconductor": 2.5, "apple": 1.5, "iphone": 1.5, "google": 1.5, "microsoft": 1.5, "meta": 1,
    "samsung": 1.5, "android": 1, "smartphone": 1, "cyberattack": 2.5, "hack": 2, "data breach": 2.5,
    "spacex": 1.5, "satellite": 1, "starlink": 1.5, "5g": 1.5, "quantum": 1.5, "tesla": 1,
    "electric vehicle": 1, "startup": 1, "software": 1, "antitrust": 2,
    "الذكاء الاصطناعي": 2.5, "شات جي بي تي": 2, "إنفيديا": 2, "رقائق": 2, "الرقائق": 2, "أشباه الموصلات": 2.5,
    "آبل": 1.5, "أبل": 1.5, "آيفون": 1.5, "جوجل": 1.5, "غوغل": 1.5, "مايكروسوفت": 1.5, "ميتا": 1, "سامسونج": 1.5,
    "أندرويد": 1, "هجوم سيبراني": 2.5, "اختراق": 1, "تسريب بيانات": 2.5, "الأمن السيبراني": 2,
    "سبيس إكس": 1.5, "قمر صناعي": 1, "ستارلينك": 1.5, "الجيل الخامس": 1.5, "شركة ناشئة": 1, "الشركات الناشئة": 1, "التحول الرقمي": 1.5, "الاتصالات": 1,
}
# Tech feeds also publish shopping/reviews/games content; drop that.
TECH_EXCLUDE = ["deal", "deals", "best ", "review:", "hands-on", "gift guide", "wordle", "trailer", "how to",
                "عرض خاص", "خصم", "مراجعة", "أفضل", "طريقة", "كيف ت", "لعبة"]
EXCLUDE = [
    "football", "soccer", "match", "league", "cup final", "goal", "film", "movie", "actor", "actress", "singer",
    "celebrity", "recipe", "fashion", "tennis", "olympic", "horoscope", "weather forecast",
    "كرة القدم", "مباراة", "الدوري", "منتخب", "لاعب", "فيلم", "مسلسل", "فنان", "فنانة", "مطرب", "الأهلي والزمالك",
    "حظك اليوم", "الأبراج", "وصفة", "حالة الطقس", "درجات الحرارة",
]
EGYPT = ["egypt", "cairo", "sisi", "alexandria", "مصر", "المصري", "المصرية", "القاهرة", "السيسي", "الإسكندرية", "مدبولي"]
REGION = [
    "gaza", "israel", "palestin", "lebanon", "syria", "iraq", "iran", "yemen", "saudi", "emirat", "uae", "qatar",
    "kuwait", "bahrain", "oman", "jordan", "sudan", "libya", "tunisia", "algeria", "morocco", "red sea", "houthi",
    "middle east", "gulf",
    "غزة", "إسرائيل", "فلسطين", "لبنان", "سوريا", "العراق", "إيران", "اليمن", "السعودية", "الإمارات", "قطر",
    "الكويت", "البحرين", "عُمان", "الأردن", "السودان", "ليبيا", "تونس", "الجزائر", "المغرب", "البحر الأحمر",
    "الحوثي", "الشرق الأوسط", "الخليج",
]


@dataclass
class Story:
    items: list[RawItem]
    title_ar: str
    summary_ar: str
    tokens: set[str]
    category: str = ""
    region: str = ""
    importance: int = 1
    score: float = 0.0
    keywords: list[str] = field(default_factory=list)


_DIACRITICS = re.compile(r"[ً-ْـ]")
_STOP = {"في", "من", "على", "الى", "إلى", "عن", "مع", "بعد", "قبل", "حول", "خلال", "بين", "هذا", "هذه", "التي",
         "الذي", "ان", "أن", "إن", "كان", "قد", "لا", "ما", "او", "أو", "the", "and", "for", "with", "from", "that"}


def normalize_tokens(text: str) -> set[str]:
    text = _DIACRITICS.sub("", text.lower())
    text = re.sub("[إأآ]", "ا", text).replace("ة", "ه").replace("ى", "ي")
    out = set()
    for w in re.findall(r"[\w]+", text):
        if w in _STOP:
            continue
        for prefix in ("وال", "بال", "لل", "ال", "و"):
            if w.startswith(prefix) and len(w) - len(prefix) >= 3:
                w = w[len(prefix):]
                break
        if len(w) >= 3:
            out.add(w)
    return out


_PATTERNS: dict[str, re.Pattern] = {}


def _matches(text: str, words) -> list[str]:
    """English keywords match at a word start ("war" must not hit "award"), and short ones must be the
    whole word ("dam" must not hit "damage"); Arabic ones match anywhere, to allow attached prefixes."""
    found = []
    for w in words:
        if w.isascii():
            end = r"s?\b" if len(w) <= 4 else ""
            pat = _PATTERNS.get(w) or _PATTERNS.setdefault(w, re.compile(r"\b" + re.escape(w) + end))
            if pat.search(text):
                found.append(w)
        elif w in text:
            found.append(w)
    return found


def group_stories(items: list[RawItem], arabic: dict[str, str]) -> list[Story]:
    """Greedy clustering: two headlines are the same story if they share enough distinctive words."""
    stories: list[Story] = []
    for it in sorted(items, key=lambda i: i.published):
        title_ar = it.title if it.source_lang == "ar" else arabic.get(it.title, it.title)
        summary_ar = it.summary if it.source_lang == "ar" else arabic.get(it.summary, it.summary)
        toks = normalize_tokens(title_ar)
        best, best_sim = None, 0.0
        for s in stories:
            shared = len(toks & s.tokens)
            sim = shared / max(1, min(len(toks), len(s.tokens)))
            if shared >= 3 and sim >= 0.5 and sim > best_sim and it.source not in {i.source for i in s.items}:
                best, best_sim = s, sim
        if best:
            best.items.append(it)
            best.tokens |= toks
            if not best.summary_ar:
                best.summary_ar = summary_ar
        else:
            stories.append(Story([it], title_ar, summary_ar, toks))
    return stories


def classify_and_score(story: Story, now: float | None = None) -> bool:
    """Fills category/region/importance/score. Returns False if the story is not politics/economy/tech."""
    now = now or time.time()
    text = " ".join(f"{i.title} {i.summary}" for i in story.items).lower() + " " + story.title_ar + " " + story.summary_ar
    if _matches(text, EXCLUDE) and not _matches(story.title_ar + " " + story.items[0].title.lower(), ["حرب", "war"]):
        return False

    econ = {w: ECONOMY[w] for w in _matches(text, ECONOMY)}
    pol = {w: POLITICS[w] for w in _matches(text, POLITICS)}
    tech = {w: TECH[w] for w in _matches(text, TECH)}
    feed_cat = next((i.source_category for i in story.items if i.source_category), None)
    if feed_cat == "tech":
        headline = story.items[0].title.lower() + " " + story.title_ar
        if _matches(headline, TECH_EXCLUDE):
            return False
    if not econ and not pol and not tech and not feed_cat:
        return False
    weights = {"politics": sum(pol.values()), "economy": sum(econ.values()), "tech": sum(tech.values())}  # ties -> first
    if feed_cat and (weights[feed_cat] > 0 or not any(weights.values())):
        story.category = feed_cat
    else:
        story.category = max(weights, key=weights.get)

    if _matches(text, EGYPT):
        story.region = "egypt"
    elif _matches(text, REGION):
        story.region = "region"
    else:
        story.region = story.items[0].source_region

    n_sources = len({i.source for i in story.items})
    top_kw = sorted({**econ, **pol, **tech}.values(), reverse=True)[:2]
    raw = 1 + 0.8 * sum(top_kw) + 1.5 * math.log2(n_sources)
    if story.region == "egypt":
        raw += 1
    elif story.region == "region":
        raw += 0.5
    story.importance = max(1, min(10, round(raw)))
    all_kw = {**econ, **pol, **tech}
    story.keywords = sorted(all_kw, key=lambda w: -all_kw[w])[:3]

    age_h = (now - min(i.published for i in story.items)) / 3600
    weight = max(i.source_weight for i in story.items)
    freshness = 0.5 ** (age_h / 12)
    story.score = round(story.importance * weight * (0.4 + 0.6 * freshness), 3)
    return True


def tier(importance: int, n_sources: int) -> str:
    if importance >= 9 or (importance >= 8 and n_sources >= 3):
        return "urgent"
    if importance >= 6:
        return "important"
    return "normal"
