#!/usr/bin/env python3
"""NosFlix – petit service de la maison (port 8097).

Facultatif : l'appli marche sans lui (tout reste alors sur chaque appareil).

- Sert le dossier des mises à jour (version.json, NosFlix.apk).
- Garde la liste « À voir plus tard » de chaque compte Jellyfin :
    GET /wishlist  → la liste du compte connecté
    PUT /wishlist  → l'appli envoie la liste complète
  Le jeton Jellyfin envoyé par l'appli est vérifié auprès de Jellyfin.
- Garde les réglages des profils enfants (GET /kids ; PUT /kids réservé aux administrateurs).
- Réécrit A-VOIR.txt à chaque changement (et chaque nuit) :
    http://<serveur>:8097/A-VOIR.txt

À installer sur la machine de Jellyfin, par exemple dans /opt/nosflix/nosflix-serveur.py,
lancé par un service systemd (voir le README). Python 3.10 ou plus, sans autre dépendance.
"""
import datetime as dt
import json
import os
import re
import tempfile
import threading
import time
import urllib.error
import urllib.request
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

PORT = 8097
WEB = "/srv/nosflix-updates"
DATA = "/srv/nosflix-data/wishlist"
TEXT = os.path.join(WEB, "A-VOIR.txt")
JELLYFIN = "http://127.0.0.1:8096"
MAX_BODY = 2_000_000
MAX_ITEMS = 3000
TOKEN_CACHE_S = 600

write_lock = threading.Lock()
token_cache: dict[str, tuple[str, str, bool, float]] = {}
KIDS = "/srv/nosflix-data/enfants.json"
ID_RE = re.compile(r"[0-9a-f]{32}")


# ---------------------------------------------------------------- Jellyfin : qui est connecté ?

def jellyfin_user(authorization: str | None) -> tuple[str, str, bool] | None:
    """(id, nom, administrateur) du compte Jellyfin du jeton, ou None s'il est refusé."""
    m = re.search(r'Token="([^"]+)"', authorization or "")
    if not m:
        return None
    token = m.group(1)
    hit = token_cache.get(token)
    if hit and hit[3] > time.time():
        return hit[0], hit[1], hit[2]
    # On transmet l'en-tête tel quel : Jellyfin voit le même appareil que l'appli.
    req = urllib.request.Request(JELLYFIN + "/Users/Me", headers={"Authorization": authorization, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            me = json.load(r)
    except urllib.error.HTTPError as e:
        if e.code in (401, 403):
            return None
        raise
    user_id = str(me.get("Id", "")).replace("-", "").lower()
    if not re.fullmatch(r"[0-9a-f]{32}", user_id):
        return None
    name = str(me.get("Name") or "")
    admin = bool((me.get("Policy") or {}).get("IsAdministrator"))
    token_cache[token] = (user_id, name, admin, time.time() + TOKEN_CACHE_S)
    return user_id, name, admin


# ---------------------------------------------------------------- Profils enfants

def read_kids() -> dict:
    """Profils enfants et titres autorisés (ou retirés) par les parents."""
    try:
        with open(KIDS, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    return {k: [i for i in data.get(k, []) if isinstance(i, str) and ID_RE.fullmatch(i)] for k in ("users", "allowed", "blocked")}


# ---------------------------------------------------------------- Fichiers

def list_path(user_id: str) -> str:
    return os.path.join(DATA, user_id + ".json")


def write_atomic(path: str, text: str) -> None:
    folder = os.path.dirname(path)
    fd, tmp = tempfile.mkstemp(dir=folder, prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def read_lists() -> list[dict]:
    lists = []
    if not os.path.isdir(DATA):
        return lists
    for name in sorted(os.listdir(DATA)):
        if not name.endswith(".json"):
            continue
        try:
            with open(os.path.join(DATA, name), encoding="utf-8") as f:
                lists.append(json.load(f))
        except (OSError, ValueError):
            continue
    return lists


# ---------------------------------------------------------------- A-VOIR.txt

MOIS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août",
        "septembre", "octobre", "novembre", "décembre"]
JOURS = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
STATUTS = {
    "Returning Series": "série en cours",
    "Ended": "série terminée",
    "Canceled": "série arrêtée",
    "In Production": "en tournage",
    "Planned": "en projet",
    "Pilot": "en projet",
}


def parse_date(iso) -> dt.date | None:
    try:
        return dt.date.fromisoformat(str(iso)[:10])
    except (TypeError, ValueError):
        return None


def date_fr(d: dt.date, weekday: bool = False) -> str:
    text = f"{'1er' if d.day == 1 else d.day} {MOIS[d.month - 1]} {d.year}"
    return f"{JOURS[d.weekday()]} {text}" if weekday else text


def millis_date(ms) -> dt.date | None:
    try:
        ms = int(ms)
    except (TypeError, ValueError):
        return None
    return dt.datetime.fromtimestamp(ms / 1000).date() if ms > 0 else None


def names(values) -> list[str]:
    return [str(v) for v in values if str(v).strip()] if isinstance(values, list) else []


def where_to_watch(item: dict, is_tv: bool) -> str | None:
    p = item.get("providers")
    if not isinstance(p, dict):
        return None
    parts = []
    sub, rent, buy = names(p.get("subscription")), names(p.get("rent")), names(p.get("buy"))
    if sub:
        parts.append(", ".join(sub[:5]))
    if rent:
        parts.append("location : " + ", ".join(rent[:4]))
    if buy:
        parts.append("achat : " + ", ".join(buy[:4]))
    if not parts:
        return None
    return ("Où la voir : " if is_tv else "Où le voir : ") + " · ".join(parts)


def item_lines(item: dict, today: dt.date, what: str) -> list[str]:
    is_tv = bool(item.get("tv"))
    title = str(item.get("title") or "Sans titre").strip()
    year = item.get("year")
    head = f"• {title}" + (f" ({year})" if year else "")
    release = parse_date(item.get("release"))
    if what == "soon" and release:
        head += f" — au cinéma le {date_fr(release, weekday=True)}"
    elif what == "cinema" and release:
        head += f" — sorti en salle le {date_fr(release)}"
    elif what == "library":
        head += " — série" if is_tv else " — film"
    lines = [head]

    details = []
    genres = names(item.get("genres"))[:3]
    if genres:
        details.append(" · ".join(genres))
    rating = item.get("rating")
    if isinstance(rating, (int, float)) and rating > 0:
        details.append(f"★ {rating:.1f}/10".replace(".", ","))
    if is_tv:
        status = STATUTS.get(str(item.get("status") or ""))
        if status:
            details.append(status)
    if details:
        lines.append("  " + " · ".join(details))

    if is_tv:
        nxt = parse_date(item.get("nextAir"))
        if nxt and nxt >= today:
            ep = str(item.get("nextEp") or "").strip()
            lines.append(f"  Prochain épisode{(' (' + ep + ')') if ep else ''} : {date_fr(nxt, weekday=True)}")
    if what != "library":
        watch = where_to_watch(item, is_tv)
        if watch:
            lines.append("  " + watch)

    added = millis_date(item.get("added"))
    link = f"https://www.themoviedb.org/{'tv' if is_tv else 'movie'}/{item.get('id')}"
    lines.append("  " + (f"Ajouté le {date_fr(added)} · " if added else "") + link)
    return lines


def section(title: str, items: list[dict], today: dt.date, what: str) -> list[str]:
    if not items:
        return []
    out = ["", f"━━ {title} ({len(items)}) ━━"]
    for it in items:
        out.append("")
        out.extend(item_lines(it, today, what))
    return out


def list_text(items: list[dict], today: dt.date) -> list[str]:
    items = [i for i in items if isinstance(i, dict)]
    if not items:
        return ["", "La liste est vide pour l'instant.",
                "Dans NosFlix, ouvre la fiche d'un film ou d'une série puis choisis « À voir plus tard »."]
    films = [i for i in items if not i.get("tv")]
    series = [i for i in items if i.get("tv")]
    out = [f"{len(items)} titre{'s' if len(items) > 1 else ''} : "
           f"{len(films)} film{'s' if len(films) > 1 else ''}, {len(series)} série{'s' if len(series) > 1 else ''}"]

    library = [i for i in items if i.get("inLibrary")]
    rest = [i for i in items if not i.get("inLibrary")]

    def release(i):
        return parse_date(i.get("release"))

    def has_subscription(i):
        p = i.get("providers")
        return isinstance(p, dict) and bool(names(p.get("subscription")))

    soon = sorted((i for i in rest if not i.get("tv") and release(i) and release(i) > today), key=release)
    cinema = sorted(
        (i for i in rest if not i.get("tv") and i not in soon and release(i)
         and (today - release(i)).days <= 60 and not has_subscription(i)),
        key=release, reverse=True)
    shown = {id(i) for i in soon + cinema}
    def next_air(i):
        d = parse_date(i.get("nextAir"))
        return d if d and d >= today else None

    # Séries : celles qui reviennent bientôt d'abord (par date), puis les autres dans l'ordre de la liste.
    tv = sorted((i for i in rest if i.get("tv")), key=lambda i: (next_air(i) is None, next_air(i) or dt.date.max))
    others = [i for i in rest if not i.get("tv") and id(i) not in shown]

    out += section("BIENTÔT AU CINÉMA", soon, today, "soon")
    out += section("AU CINÉMA EN CE MOMENT", cinema, today, "cinema")
    out += section("SÉRIES À SUIVRE", tv, today, "series")
    out += section("FILMS", others, today, "films")
    out += section("DÉJÀ DANS NOSFLIX", library, today, "library")
    return out


def write_text() -> None:
    now = dt.datetime.now()
    today = now.date()
    # Une liste par profil : on ne montre que celles qui ont des titres.
    lists = [l for l in read_lists() if isinstance(l, dict) and l.get("items")]
    lines = ["À VOIR PLUS TARD — NosFlix",
             f"Mis à jour le {date_fr(today, weekday=True)} à {now:%H:%M}"]
    if not lists:
        lines += list_text([], today)
    elif len(lists) == 1:
        if len(read_lists()) > 1:
            lines += [f"Liste de {lists[0].get('user') or 'quelqu’un'}"]
        lines += list_text(lists[0].get("items") or [], today)
    else:
        for l in lists:
            lines += ["", "", f"════ Liste de {l.get('user') or 'quelqu’un'} ════"]
            lines += list_text(l.get("items") or [], today)
    lines += ["", "", "—",
              "Cette liste se remplit depuis l'appli NosFlix (fiche d'un titre → « À voir plus tard »).",
              "Dates de sortie et plateformes : TMDB, pour la France."]
    # BOM : le Bloc-notes et les navigateurs reconnaissent tout de suite l'UTF-8 (accents).
    write_atomic(TEXT, "\ufeff" + "\n".join(lines) + "\n")


def nightly() -> None:
    """Une fois par jour, les dates « bientôt / au cinéma » se recalent même sans changement."""
    last = dt.date.today()
    while True:
        time.sleep(600)
        if dt.date.today() != last:
            last = dt.date.today()
            try:
                with write_lock:
                    write_text()
            except Exception as e:  # noqa: BLE001 – le service ne doit pas tomber pour un fichier texte
                print("A-VOIR.txt :", e, flush=True)


# ---------------------------------------------------------------- HTTP

class Handler(SimpleHTTPRequestHandler):
    extensions_map = {
        **SimpleHTTPRequestHandler.extensions_map,
        ".txt": "text/plain; charset=utf-8",
        ".json": "application/json; charset=utf-8",
        ".apk": "application/vnd.android.package-archive",
    }

    def send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def end_headers(self) -> None:
        if self.path.split("?")[0] in ("/version.json", "/A-VOIR.txt"):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def route(self) -> str:
        return self.path.split("?")[0].rstrip("/")

    def is_wishlist(self) -> bool:
        return self.route() == "/wishlist"

    def read_body(self) -> dict | None:
        """Corps JSON de la requête (objet), ou None après avoir répondu l'erreur."""
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if length <= 0 or length > MAX_BODY:
            self.send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "Envoi trop grand."})
            return None
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(body, dict):
                raise ValueError
            return body
        except (ValueError, UnicodeDecodeError):
            self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Envoi illisible."})
            return None

    def kids_get(self) -> None:
        if self.who() is None:
            return
        self.send_json(HTTPStatus.OK, read_kids())

    def kids_put(self) -> None:
        """Seul un parent (compte administrateur) choisit les profils enfants et leurs titres."""
        user = self.who()
        if user is None:
            return
        if not user[2]:
            return self.send_json(HTTPStatus.FORBIDDEN, {"error": "Réservé aux parents."})
        body = self.read_body()
        if body is None:
            return
        data = {}
        for k in ("users", "allowed", "blocked"):
            values = body.get(k, [])
            if not isinstance(values, list) or len(values) > MAX_ITEMS or not all(isinstance(i, str) and ID_RE.fullmatch(i) for i in values):
                return self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Liste illisible."})
            data[k] = sorted(set(values))
        data["updated"] = dt.datetime.now().isoformat(timespec="seconds")
        with write_lock:
            write_atomic(KIDS, json.dumps(data, indent=1))
        self.send_json(HTTPStatus.OK, {"ok": True})

    def who(self) -> tuple[str, str, bool] | None:
        try:
            user = jellyfin_user(self.headers.get("Authorization"))
        except Exception:  # noqa: BLE001
            self.send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "Jellyfin ne répond pas."})
            return None
        if user is None:
            self.send_json(HTTPStatus.UNAUTHORIZED, {"error": "Connexion Jellyfin refusée."})
        return user

    def do_GET(self) -> None:
        if self.route() == "/kids":
            return self.kids_get()
        if not self.is_wishlist():
            return super().do_GET()
        user = self.who()
        if user is None:
            return
        path = list_path(user[0])
        if not os.path.exists(path):
            return self.send_json(HTTPStatus.NOT_FOUND, {"error": "Pas encore de liste."})
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        self.send_json(HTTPStatus.OK, data)

    def do_PUT(self) -> None:
        if self.route() == "/kids":
            return self.kids_put()
        if not self.is_wishlist():
            return self.send_json(HTTPStatus.METHOD_NOT_ALLOWED, {"error": "Méthode non prise en charge."})
        user = self.who()
        if user is None:
            return
        body = self.read_body()
        if body is None:
            return
        items = body.get("items")
        if not isinstance(items, list) or len(items) > MAX_ITEMS or not all(isinstance(i, dict) for i in items):
            return self.send_json(HTTPStatus.BAD_REQUEST, {"error": "Liste illisible."})
        data = {
            "user": user[1],
            "userId": user[0],
            "updated": dt.datetime.now().isoformat(timespec="seconds"),
            "items": items,
        }
        with write_lock:
            write_atomic(list_path(user[0]), json.dumps(data, ensure_ascii=False, indent=1))
            write_text()
        self.send_json(HTTPStatus.OK, {"ok": True, "count": len(items)})


def main() -> None:
    os.makedirs(DATA, exist_ok=True)
    with write_lock:
        write_text()
    threading.Thread(target=nightly, daemon=True).start()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), partial(Handler, directory=WEB))
    print(f"NosFlix : service prêt sur le port {PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
