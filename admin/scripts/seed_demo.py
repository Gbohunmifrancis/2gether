#!/usr/bin/env python3
"""
Demo data for the admin console: about 240 people over the last 90 days, believable enough to design and review
the dashboard against. Development only. It needs a local API started like seed.py says (Billing__Provider=fake,
Email__Provider=log, RateLimits__Enabled=false, Admin__Emails=ops@heartpact.app, plus the monthly prices for the
revenue estimate) and psql access to that API's database.

    python3 admin/scripts/seed_demo.py --reset                  # wipe the data (schema stays), then seed
    python3 admin/scripts/seed_demo.py --reset --people 120 --seed 7 http://localhost:5135
    python3 admin/scripts/seed_demo.py --dry-run                # print what it would create, touch nothing

--psql (or PSQL_DEMO) is the psql command line; the default is the dev database
"psql -h /var/lib/postgresql/heartpact-dev -p 5544 -U postgres -d heartpact". The same --seed gives the same people
and the same story every time. Without --reset it refuses to run over data it seeded before.

What it makes
- People: sign-ups rising from a few a day, a launch spike about 38 days ago, quieter weekends, from 27 countries
  (Nigeria, the US, the UK, India, Brazil, Mexico, France, Egypt first), languages to match, about half with Google
  sign-in (some Google only). Three team accounts are marked internal (users."IsInternal"), so analytics leave them out.
- Couples: most link within minutes or days of signing up; some invites are never accepted, a few couples break up
  (some of them invite someone again), three people delete their accounts, one spam account is disabled by the admin.
- Activity with a retention decay (power, regular and casual couples, each with a lifetime): dares set and completed,
  missed or called off at the hours people really keep (morning and evening alarms in their own time zones, early
  starts, minutes to start, time on camera, confidence and camera checks), meals logged by hand and from food
  scans, daily questions, games (Heart to heart duels with skill scores, tic-tac-toe, connect four, would you
  rather, truth or dare, same answer, and some abandoned), messages, cycle tracking, location sharing, a few
  pregnancies, photos, Google Play subscriptions (active, canceled, grace, on hold, expired) and complimentary Plus.

How: through the real API, then moved back in time
Everything the API can do is done through it, now, and then its rows are moved to when it "happened" with SQL:
CreatedAtUtc, LastActiveAtUtc, linked/started/completed/logged times, keeping each row consistent (created before
started before completed, inside the dare's window, notifications at the moment they were sent). Things that exist
once per day (a dare's occurrence, a daily question) are done for today and moved to their day, then the next one
is done. Dares go through start-early and complete (the start time is wound back so the "too fast" check passes);
missed dares are left for the server's own alarm worker to miss; called-off ones are asked off and let off by the
partner. Games are invited, accepted and played move by move. Purchases use the fake billing tokens
fake.{active|canceled|grace|onhold|expired}.{days}.{accountId}.{productId}; complimentary Plus and the disabled
account go through the admin API.

Written with SQL instead (the API can't, and the rows are consistent and harmless)
- food scans: the scan endpoint needs a real model key, so scan rows (Model "fake-estimator", token counts, latency,
  the estimate) are inserted and the meal is then logged through the API from that scan;
- photos: memories rows in Ready state with fake object keys and sizes (there is no storage behind them, so the
  app shows broken images; only couples that are still together get any, so the clean-up worker leaves them);
- user_activity_days (one row per person per active day) and the sign-in method (GoogleId, no password for Google-only).
- users."IsInternal" is added if the database doesn't have it yet (the same statement as the schema upgrade).
Skipped: game_results (the app doesn't write it), trivia, word dash, memory match, guess my answer, draw and guess
and I call on played to the end (they need outside word lists, clocks or hidden cards; a few are started and
abandoned), kick counts, pregnancy appointments. Location sharing has no coordinates (they only come over the
realtime hub); a few people start sharing at the end of the run, and those shares lapse ten minutes later like a
phone that went quiet. Goals keep their real alarm times, so dares start ringing (and being missed) once it's done.
Inviting to Heart to heart, Truth or dare or Draw and guess answers 500 although the invite is saved (an app bug in
their ToPublicState before the game starts); the script finds the saved invite and carries on.

It takes about two minutes. At the end it prints the row count of every table, consistency checks (rows still
stamped "now", timestamps out of order: all should be 0), the 30-day admin numbers, and the admin's email,
password, authenticator secret and a token, like seed.py. Every demo account's password is "longenough".
"""
import argparse
import base64
import collections
import concurrent.futures
import datetime as dt
import functools
import hashlib
import hmac
import json
import math
import os
import random
import shlex
import struct
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo

UTC = dt.timezone.utc
ADMIN = "ops@heartpact.app"
PASSWORD = "longenough"
MARKER = "qa.team@heartpact.app"  # the first internal account: if it's there, this script seeded the database
DEFAULT_PSQL = "psql -h /var/lib/postgresql/heartpact-dev -p 5544 -U postgres -d heartpact"
FAR = dt.datetime(2100, 1, 1, tzinfo=UTC)  # parks a goal's alarm pointer while the script works on it
DAY = dt.timedelta(days=1)
MIN = dt.timedelta(minutes=1)
SEC = dt.timedelta(seconds=1)
PRODUCTS = {"heartpact_plus_monthly": 30, "heartpact_plus_yearly": 365}
FREE_SCANS, PLUS_SCANS = 3, 20
INVITE_LIFETIME = dt.timedelta(hours=48)


# ---------------------------------------------------------------------------------------------------------------------
# World: countries, names, food, words


def zones(*pairs):
    return list(pairs) if isinstance(pairs[0], tuple) else [(pairs[0], 1)]


# code, weight, languages, time zones, name pool, cuisine
COUNTRIES = [
    ("NG", 22, [("en", 1)], zones("Africa/Lagos"), "ng", "ng"),
    ("US", 13, [("en", .92), ("es", .08)], zones(("America/New_York", .4), ("America/Chicago", .25), ("America/Denver", .08), ("America/Los_Angeles", .27)), "us", "us"),
    ("GB", 9, [("en", 1)], zones("Europe/London"), "gb", "gb"),
    ("IN", 8, [("hi", .55), ("en", .45)], zones("Asia/Kolkata"), "in", "in"),
    ("BR", 7, [("pt", 1)], zones("America/Sao_Paulo"), "br", "br"),
    ("MX", 5, [("es", 1)], zones("America/Mexico_City"), "mx", "mx"),
    ("FR", 5, [("fr", 1)], zones("Europe/Paris"), "fr", "fr"),
    ("EG", 4, [("ar", 1)], zones("Africa/Cairo"), "eg", "eg"),
    ("GH", 3.5, [("en", 1)], zones("Africa/Accra"), "gh", "gh"),
    ("CA", 3, [("en", .8), ("fr", .2)], zones(("America/Toronto", .6), ("America/Vancouver", .25), ("America/Edmonton", .15)), "us", "us"),
    ("KE", 2.5, [("en", 1)], zones("Africa/Nairobi"), "ke", "gh"),
    ("ZA", 2.5, [("en", 1)], zones("Africa/Johannesburg"), "za", "gh"),
    ("DE", 2, [("en", 1)], zones("Europe/Berlin"), "de", "de"),
    ("ES", 2, [("es", 1)], zones("Europe/Madrid"), "es", "mx"),
    ("AE", 1.6, [("ar", .5), ("en", .5)], zones("Asia/Dubai"), "eg", "eg"),
    ("PH", 1.5, [("en", 1)], zones("Asia/Manila"), "ph", "ph"),
    ("AU", 1.4, [("en", 1)], zones("Australia/Sydney"), "gb", "us"),
    ("SA", 1.2, [("ar", 1)], zones("Asia/Riyadh"), "eg", "eg"),
    ("MA", 1.2, [("fr", .6), ("ar", .4)], zones("Africa/Casablanca"), "ma", "fr"),
    ("CO", 1.2, [("es", 1)], zones("America/Bogota"), "mx", "mx"),
    ("AR", 1.0, [("es", 1)], zones("America/Argentina/Buenos_Aires"), "mx", "mx"),
    ("PT", 1.0, [("pt", 1)], zones("Europe/Lisbon"), "br", "br"),
    ("IE", .8, [("en", 1)], zones("Europe/Dublin"), "gb", "gb"),
    ("NL", .7, [("en", 1)], zones("Europe/Amsterdam"), "de", "de"),
    ("JM", .6, [("en", 1)], zones("America/Jamaica"), "us", "us"),
    ("SN", .6, [("fr", 1)], zones("Africa/Dakar"), "sn", "fr"),
    ("CI", .6, [("fr", 1)], zones("Africa/Abidjan"), "sn", "fr"),
]
BY_CODE = {row[0]: row for row in COUNTRIES}
# Where a long-distance partner tends to live.
ABROAD = {"NG": ["GB", "US", "CA", "GH"], "IN": ["US", "GB", "AE"], "MX": ["US"], "FR": ["MA", "SN", "CI", "CA"], "EG": ["AE", "SA"],
          "PH": ["US", "AU"], "BR": ["PT", "US"], "GH": ["GB", "NG"], "KE": ["GB"], "ZA": ["GB", "AU"], "US": ["NG", "MX", "GB"], "GB": ["NG", "IE", "US"]}

NAMES = {
    "ng": ("Chioma Ngozi Adaeze Funke Kemi Bisola Zainab Amaka Ifeoma Tolu Yewande Halima Nneka Temi Damilola Ada Bimpe Uju",
           "Tunde Emeka Femi Segun Chidi Ikenna Musa Kunle Obinna Tobi Dayo Uche Ahmed Seun Gbenga Kayode Nnamdi Sola",
           "Okafor Adeyemi Balogun Eze Okonkwo Bello Ogunleye Nwosu Adebayo Ibrahim Olawale Chukwu Afolabi Danjuma Okeke Lawal"),
    "us": ("Emily Ashley Jessica Taylor Madison Brianna Olivia Hannah Kayla Megan Lauren Jasmine Sydney Alexis",
           "Michael Tyler Jordan Brandon Ethan Marcus Ryan Austin Kevin Justin Daniel Andre Caleb Isaiah",
           "Johnson Williams Brown Miller Davis Garcia Wilson Moore Taylor Anderson Thomas Jackson Harris Robinson"),
    "gb": ("Sophie Charlotte Amelia Chloe Jess Holly Ellie Grace Lucy Freya Maisie Niamh",
           "Oliver Harry Jack George Callum Liam Josh Alfie Ben Tom Kieran Rhys",
           "Smith Jones Evans Hughes Walker Wright Roberts Green Hall Clarke Patel Murphy"),
    "in": ("Priya Ananya Diya Kavya Isha Neha Pooja Riya Sneha Aditi Meera Tanvi",
           "Aarav Rohan Arjun Vikram Rahul Karan Aditya Nikhil Siddharth Varun Ishaan Kabir",
           "Sharma Patel Gupta Singh Kumar Reddy Iyer Nair Mehta Joshi Rao Banerjee"),
    "br": ("Ana Beatriz Juliana Larissa Camila Fernanda Gabriela Mariana Letícia Bruna",
           "João Pedro Lucas Gabriel Rafael Thiago Bruno Felipe Matheus Gustavo",
           "Silva Santos Oliveira Souza Lima Pereira Costa Almeida Ferreira Rocha"),
    "mx": ("María Sofía Valeria Daniela Fernanda Lucía Ximena Camila Regina Paola",
           "José Luis Diego Carlos Alejandro Javier Miguel Andrés Emiliano Santiago",
           "Hernández García Martínez López González Rodríguez Pérez Sánchez Ramírez Torres"),
    "es": ("Lucía Martina Paula Alba Carmen Elena Nerea Irene",
           "Pablo Álvaro Hugo Adrián Sergio Javier Marcos Iván",
           "García Fernández López Martín Sánchez Romero Navarro Ruiz Moreno Jiménez"),
    "fr": ("Camille Léa Manon Chloé Inès Sarah Julie Emma Clara Lina",
           "Lucas Hugo Théo Nathan Louis Maxime Antoine Julien Mathis Rayan",
           "Martin Bernard Dubois Thomas Robert Petit Durand Moreau Laurent Lefebvre"),
    "eg": ("Nour Salma Mariam Yasmin Farida Hana Layla Aya Malak Reem",
           "Omar Youssef Karim Ahmed Mostafa Ali Hassan Tarek Ziad Khaled",
           "Hassan Mahmoud Ibrahim Saleh Mansour Farouk Nasser Haddad Fathy Abdelrahman"),
    "gh": ("Akosua Abena Ama Efua Adwoa Esi Afia Yaa", "Kwame Kofi Kwabena Yaw Kojo Nana Kwesi Ekow",
           "Mensah Owusu Boateng Asante Osei Appiah Agyeman Darko"),
    "ke": ("Wanjiru Achieng Njeri Akinyi Mumbi Wambui Atieno", "Kamau Otieno Mwangi Kiprop Ochieng Njoroge Baraka",
           "Kariuki Odhiambo Mutua Kimani Wekesa Chege Omondi"),
    "za": ("Thandi Lerato Naledi Zanele Ayanda Palesa Nandi", "Sipho Thabo Lwazi Kagiso Bongani Musa Themba",
           "Nkosi Dlamini Ndlovu Mokoena Botha Khumalo Mahlangu"),
    "de": ("Lena Anna Laura Mia Hannah Sophie Lotte Femke", "Lukas Jonas Leon Finn Daan Lars Felix Max",
           "Müller Schmidt Schneider Fischer Weber de Vries Jansen Bakker"),
    "ma": ("Salma Imane Khadija Yasmine Sara Nadia", "Youssef Mehdi Amine Hamza Rachid Karim",
           "Benali El Amrani Alaoui Bennani Tazi Idrissi"),
    "ph": ("Andrea Nicole Kristine Angelica Joy Mae", "Mark Carlo Miguel Paolo Jericho Rafael",
           "Santos Reyes Cruz Bautista Garcia Mendoza"),
    "sn": ("Aminata Fatou Awa Mariama Aïssatou Adja", "Moussa Ibrahima Cheikh Mamadou Ousmane Abdoulaye",
           "Diop Ndiaye Fall Sow Diallo Koné"),
}
NAMES = {key: tuple(part.split() for part in value) for key, value in NAMES.items()}
NAMES["de"] = (NAMES["de"][0], NAMES["de"][1], ["Müller", "Schmidt", "Schneider", "Fischer", "Weber", "de Vries", "Jansen", "Bakker"])
NAMES["ma"] = (NAMES["ma"][0], NAMES["ma"][1], ["Benali", "El Amrani", "Alaoui", "Bennani", "Tazi", "Idrissi"])

# description, meal type, calories (the person's own words, which the admin never sees)
MEALS = {
    "ng": [("Jollof rice and chicken", "lunch", 720), ("Egusi soup and pounded yam", "dinner", 850), ("Akara and pap", "breakfast", 420),
           ("Suya", "snack", 450), ("Fried plantain and eggs", "breakfast", 520), ("Moi moi", "lunch", 320), ("Ofada rice and ayamase", "dinner", 780),
           ("Goat meat pepper soup", "dinner", 380), ("Puff-puff", "snack", 350), ("Beans and dodo", "lunch", 600), ("Indomie and egg", "dinner", 480),
           ("Bread and tea", "breakfast", 330), ("Amala and ewedu", "dinner", 690)],
    "us": [("Avocado toast", "breakfast", 380), ("Greek yogurt with berries", "breakfast", 250), ("Oatmeal with banana", "breakfast", 320),
           ("Turkey sandwich", "lunch", 520), ("Chicken Caesar salad", "lunch", 480), ("Burrito bowl", "lunch", 780), ("Spaghetti bolognese", "dinner", 690),
           ("Salmon with rice", "dinner", 640), ("Two slices of pepperoni pizza", "dinner", 560), ("Cheeseburger and fries", "dinner", 980),
           ("Protein shake", "snack", 200), ("Apple and peanut butter", "snack", 260), ("Chicken tacos", "dinner", 620)],
    "gb": [("Porridge with honey", "breakfast", 310), ("Full English", "breakfast", 820), ("Meal deal sandwich and crisps", "lunch", 610),
           ("Jacket potato with beans", "lunch", 520), ("Fish and chips", "dinner", 950), ("Chicken tikka masala", "dinner", 760),
           ("Sunday roast", "dinner", 880), ("Cheese toastie", "lunch", 450), ("Tea and biscuits", "snack", 180), ("Shepherd's pie", "dinner", 640)],
    "in": [("Masala dosa", "breakfast", 420), ("Poha", "breakfast", 300), ("Aloo paratha", "breakfast", 450), ("Chole bhature", "lunch", 750),
           ("Rajma chawal", "lunch", 600), ("Paneer butter masala with naan", "dinner", 820), ("Dal tadka and rice", "dinner", 560),
           ("Chicken biryani", "dinner", 780), ("Two samosas", "snack", 320), ("Masala chai and biscuits", "snack", 180), ("Idli sambar", "breakfast", 350)],
    "br": [("Pão de queijo e café", "breakfast", 280), ("Açaí na tigela", "snack", 480), ("Feijoada", "lunch", 850), ("Arroz, feijão e frango", "lunch", 720),
           ("Coxinha", "snack", 300), ("Tapioca com queijo", "breakfast", 350), ("Pastel de nata", "snack", 220), ("Bacalhau à brás", "dinner", 680),
           ("Strogonoff de frango", "dinner", 740), ("Pão na chapa", "breakfast", 260)],
    "mx": [("Chilaquiles", "breakfast", 620), ("Tacos al pastor", "dinner", 540), ("Enchiladas verdes", "lunch", 680), ("Huevos rancheros", "breakfast", 520),
           ("Pozole", "dinner", 560), ("Torta de jamón", "lunch", 640), ("Churros", "snack", 380), ("Tortilla española", "dinner", 450), ("Paella", "lunch", 720),
           ("Empanadas", "lunch", 520), ("Arepa con queso", "breakfast", 420), ("Milanesa con papas", "dinner", 850)],
    "fr": [("Croissant et café", "breakfast", 320), ("Tartines beurre confiture", "breakfast", 300), ("Quiche lorraine", "lunch", 520),
           ("Salade niçoise", "lunch", 450), ("Steak frites", "dinner", 900), ("Croque-monsieur", "lunch", 580), ("Thiéboudienne", "lunch", 780),
           ("Poulet yassa", "dinner", 690), ("Tajine poulet olives", "dinner", 650), ("Couscous royal", "dinner", 820), ("Msemen et thé", "breakfast", 380),
           ("Pain au chocolat", "snack", 280)],
    "eg": [("Ful medames", "breakfast", 420), ("Koshari", "lunch", 700), ("Falafel sandwich", "breakfast", 520), ("Shawarma wrap", "dinner", 650),
           ("Molokhia with rice", "lunch", 580), ("Mahshi", "dinner", 520), ("Kabsa", "dinner", 820), ("Hummus and pita", "snack", 380), ("Dates and laban", "snack", 220)],
    "gh": [("Waakye", "lunch", 720), ("Banku and tilapia", "dinner", 820), ("Kelewele", "snack", 350), ("Ugali and sukuma wiki", "dinner", 600),
           ("Nyama choma", "dinner", 750), ("Chapati and beans", "lunch", 640), ("Bunny chow", "lunch", 850), ("Pap and wors", "dinner", 780),
           ("Hausa koko and koose", "breakfast", 380), ("Mandazi and chai", "breakfast", 300)],
    "ph": [("Chicken adobo with rice", "dinner", 720), ("Tapsilog", "breakfast", 680), ("Sinigang", "dinner", 450), ("Pancit canton", "lunch", 560),
           ("Pandesal and coffee", "breakfast", 250), ("Halo-halo", "snack", 380)],
    "de": [("Müsli", "breakfast", 350), ("Brötchen mit Käse", "breakfast", 380), ("Currywurst mit Pommes", "lunch", 820), ("Schnitzel", "dinner", 900),
           ("Stamppot", "dinner", 650), ("Poffertjes", "snack", 400), ("Döner", "lunch", 740)],
}
MESSAGES = {
    "en": ["Good morning love", "Miss you", "Did you do your squats? 😏", "Dinner tonight?", "On my way", "Love you", "Call me when you're free",
           "Haha", "❤️", "Proud of you", "Can't wait to see you", "Sleep well", "That dare was evil 😂", "Your turn!", "Text me when you land"],
    "fr": ["Bonjour mon cœur", "Tu me manques", "Je t'aime", "On mange quoi ce soir ?", "J'arrive", "Bonne nuit", "Haha", "❤️", "Ton tour !"],
    "pt": ["Bom dia amor", "Saudades", "Te amo", "Jantar hoje?", "Tô chegando", "Boa noite", "Kkkkk", "❤️", "Sua vez!"],
    "es": ["Buenos días amor", "Te extraño", "Te quiero", "¿Cenamos hoy?", "Ya voy", "Buenas noches", "Jajaja", "❤️", "¡Te toca!"],
    "ar": ["صباح الخير يا حبيبي", "وحشتني", "بحبك", "نتعشى سوا؟", "في الطريق", "تصبح على خير", "❤️"],
    "hi": ["Good morning jaan", "Miss you yaar", "Love you", "Khana khaya?", "Aa raha hoon", "Good night", "❤️", "Tumhari baari!"],
}
ANSWERS = ["You, obviously", "The beach trip", "Your laugh", "Pizza night", "Honestly? Sleep", "When you cooked for me", "Somewhere warm",
           "That first date", "Our song", "More dates, less phones", "Your hugs", "Dancing in the kitchen", "A weekend away", "Breakfast in bed"]
QUIZ_WORDS = ["pizza", "beach", "netflix", "sleep", "jollof", "paris", "dogs", "summer", "you", "coffee"]
SYMPTOMS = ["cramps", "headache", "bloating", "fatigue", "acne", "backache", "cravings", "tender breasts"]
VERSIONS = [(0, "1.0.4"), (24, "1.1.0"), (51, "1.2.0"), (76, "1.3.1")]  # (release day, version)

# Exercise: (type, min seconds per unit as the server checks them, typical targets, reps or seconds)
EXERCISES = {"squat": (.8, [10, 15, 20, 25, 30], "reps"), "pushUp": (.7, [5, 10, 15, 20], "reps"), "jumpingJack": (.4, [20, 30, 40, 50], "reps"),
             "plank": (.9, [20, 30, 45, 60, 90], "seconds"), "armRaise": (.5, [10, 15, 20, 30], "reps"), "highKnees": (.25, [20, 30, 40, 60], "reps"),
             "sitUp": (.8, [10, 15, 20, 25], "reps")}
EXERCISE_WEIGHTS = {"squat": .26, "pushUp": .2, "jumpingJack": .14, "plank": .14, "sitUp": .1, "highKnees": .09, "armRaise": .07}
SKILL_MEANS = {"FairShare": 74, "FillTheHeart": 69, "OtherHalf": 61, "Heartbeat": 76, "KissTheCenter": 65, "PromiseRing": 57}
GAMES = [("skillDuel", .30), ("ticTacToe", .17), ("wouldYouRather", .15), ("truthOrDare", .11), ("coupleQuiz", .1), ("connectFour", .08),
         ("memoryMatch", .03), ("guessMyAnswer", .03), ("wordDash", .02), ("handwriting", .01)]
PLAYED = {"skillDuel", "ticTacToe", "wouldYouRather", "truthOrDare", "coupleQuiz", "connectFour"}

# Local hour weights, 0..23.
OPEN_HOURS = [1, .5, .3, .2, .2, .4, 1.2, 2.2, 2.4, 2, 1.6, 1.6, 2, 1.8, 1.4, 1.4, 1.6, 2, 2.4, 2.8, 3, 3, 2.4, 1.6]
EVENING = [.6, .3, .1, .1, .1, .1, .2, .4, .5, .5, .5, .6, 1, .9, .7, .7, .8, 1.1, 1.6, 2.2, 2.8, 3, 2.6, 1.6]
MEAL_HOURS = {"breakfast": (6.5, 9.8), "lunch": (11.8, 14.5), "dinner": (18.3, 22), "snack": (15, 17.5)}

SEGMENTS = {  # share, mean lifetime (days), base chance of a day in use, partner engagement, dares, games, messages, photos, questions
    "power": dict(share=.22, life=170, base=.9, goals=.96, games=.32, msgs=2.6, photos=.3, questions=.65, food=.55, loc=.45, buy=.4, complete=.86),
    "regular": dict(share=.43, life=40, base=.62, goals=.8, games=.16, msgs=1.4, photos=.15, questions=.45, food=.4, loc=.3, buy=.15, complete=.73),
    "casual": dict(share=.35, life=9, base=.48, goals=.6, games=.08, msgs=.7, photos=.07, questions=.3, food=.25, loc=.15, buy=.03, complete=.58),
}


# ---------------------------------------------------------------------------------------------------------------------
# Plumbing: HTTP, psql, stats


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.counts = collections.Counter()

    def add(self, key, n=1):
        with self.lock:
            self.counts[key] += n


STATS = Stats()
ARGS = None
API = ""


def call(method, path, body=None, token=None, lang=None, headers=None):
    hdrs = {"content-type": "application/json"}
    if token:
        hdrs["authorization"] = f"Bearer {token}"
    if lang:
        hdrs["accept-language"] = lang
    hdrs.update(headers or {})
    data = None if body is None else json.dumps(body).encode()
    for attempt in range(4):
        req = urllib.request.Request(API + path, method=method, data=data, headers=hdrs)
        try:
            with urllib.request.urlopen(req, timeout=60) as res:
                text = res.read().decode()
                STATS.add("api.calls")
                return res.status, (json.loads(text) if text else None)
        except urllib.error.HTTPError as error:
            text = error.read().decode()
            STATS.add("api.calls")
            try:
                return error.code, (json.loads(text) if text else None)
            except ValueError:
                return error.code, text
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            if attempt == 3:
                raise
            time.sleep(0.5 * (attempt + 1))


def ok(status):
    return 200 <= status < 300


class Psql:
    """One long-lived psql per thread, fed statements on stdin: a statement costs well under a millisecond."""
    END = "__seed_demo_end__"

    def __init__(self, command):
        self.process = subprocess.Popen(shlex.split(command) + ["-X", "-q", "-At", "-F", "\x1f", "-v", "ON_ERROR_STOP=0"],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, encoding="utf-8")
        self.run("SET client_min_messages = warning")

    def run(self, statement):
        self.process.stdin.write(statement.strip().rstrip(";") + ";\n\\echo " + self.END + "\n")
        self.process.stdin.flush()
        lines = []
        while True:
            line = self.process.stdout.readline()
            if line == "":
                raise RuntimeError("psql exited; is the database reachable with: " + ARGS.psql)
            line = line.rstrip("\n")
            if line == self.END:
                break
            lines.append(line)
        errors = [line for line in lines if "ERROR:" in line or line.startswith("psql:")]
        if errors:
            raise RuntimeError(f"SQL failed: {errors[0]}\n{statement[:1500]}")
        return [line.split("\x1f") for line in lines if line]


LOCAL = threading.local()


def sql(statement):
    if not hasattr(LOCAL, "psql"):
        LOCAL.psql = Psql(ARGS.psql)
    return LOCAL.psql.run(statement)


def q(value):
    """A SQL literal."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, dt.datetime):
        return "'" + value.astimezone(UTC).strftime("%Y-%m-%d %H:%M:%S.%f") + "+00'::timestamptz"
    if isinstance(value, dt.date):
        return f"'{value.isoformat()}'::date"
    return "'" + str(value).replace("'", "''") + "'"


def qu(value):
    return "NULL" if value is None else f"'{value}'::uuid"


def values(rows):
    return ",\n".join("(" + ", ".join(row) + ")" for row in rows)


def totp(secret, at=None):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    step = int((at or time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


@functools.lru_cache(maxsize=None)
def zone(tz):
    return ZoneInfo(tz)


def at_local(tz, date, hours):
    naive = dt.datetime.combine(date, dt.time()) + dt.timedelta(hours=hours)
    return naive.replace(tzinfo=zone(tz)).astimezone(UTC)


def local_date(tz, moment):
    return moment.astimezone(zone(tz)).date()


def next_due(hhmm, tz, after):
    """The first time at local hh:mm on or after `after` (as ExerciseSchedule.NextDueUtc)."""
    date = local_date(tz, after) - DAY
    for _ in range(5):
        candidate = at_local(tz, date, hhmm[0] + hhmm[1] / 60)
        if candidate >= after:
            return candidate
        date += DAY
    raise ValueError("no next due time")


def weighted(rng, pairs):
    items, weights = zip(*pairs)
    return rng.choices(items, weights)[0]


def hour_in(rng, weights):
    return rng.choices(range(24), weights)[0] + rng.random()


def ascii_slug(text):
    return "".join(ch for ch in unicodedata.normalize("NFKD", text) if ch.isascii() and (ch.isalnum())).lower()


# ---------------------------------------------------------------------------------------------------------------------
# The plan: who, when, and what they do. Pure and seeded; nothing touches the API here.


@dataclass(eq=False)
class Person:
    index: int
    name: str
    email: str
    gender: str
    dob: dt.date
    country: str | None
    lang: str
    tz: str
    signup: dt.datetime
    method: str = "password"  # password | google | both
    internal: bool = False
    skill: float = 0.0
    food: bool = False
    scanner: bool = False
    cycle: dict | None = None
    delete_at: dt.datetime | None = None
    disable_at: dt.datetime | None = None
    acts: list = field(default_factory=list)
    version: str = "1.3.1"
    # Filled in as the script runs.
    id: str | None = None
    token: str | None = None
    couple0: str | None = None
    target_set: dt.datetime | None = None
    cycle_set: dt.datetime | None = None
    deleted: bool = False

    @property
    def accept_language(self):
        region = self.country or "US"
        return f"{self.lang}-{region},{self.lang};q=0.9"

    def act(self, moment):
        self.acts.append(moment)


@dataclass(eq=False)
class Couple:
    index: int
    a: Person  # invites
    b: Person | None  # accepts; None when the invite is never accepted
    invite_at: dt.datetime
    link_at: dt.datetime | None = None
    segment: str = "casual"
    churn_at: dt.datetime | None = None
    breakup_at: dt.datetime | None = None
    breakup_by: Person | None = None
    end: dt.datetime | None = None
    events: list = field(default_factory=list)
    memories: list = field(default_factory=list)
    grant: dict | None = None
    # Filled in as the script runs.
    id: str | None = None
    goals: dict = field(default_factory=dict)
    stamps_food: list = field(default_factory=list)
    stamps_messages: list = field(default_factory=list)
    plus_until: dt.datetime | None = None
    plus_from: dt.datetime | None = None

    def add(self, moment, event, /, **data):
        self.events.append((moment, len(self.events), event, data))


class Planner:
    def __init__(self, seed, people, days, now):
        self.rng = random.Random(seed)
        self.seed = seed
        self.n = people
        self.days = days
        self.now = now
        self.horizon = now - 2 * MIN  # everything simulated happened before this
        self.today = now.date()
        self.day0 = self.today - dt.timedelta(days=days - 1)
        self.launch = int(days * 0.58)
        self.people = []
        self.couples = []
        self.singles = []
        self.emails = set()
        self.churn = {}

    # -- people

    def day_weights(self):
        rng = self.rng
        weights = []
        for d in range(self.days):
            date = self.day0 + dt.timedelta(days=d)
            w = 0.8 + 2.9 * (d / (self.days - 1)) ** 1.4
            w *= {0: 5.5, 1: 3.4, 2: 2.2, 3: 1.6, 4: 1.25}.get(d - self.launch, 1.0)
            if date.weekday() >= 5:
                w *= 0.68
            weights.append(w * rng.uniform(0.85, 1.15))
        elapsed = (self.now - dt.datetime.combine(self.today, dt.time(), UTC)) / DAY
        weights[-1] *= max(0.1, elapsed)
        return weights

    def spread(self, weights, count):
        """`count` days drawn along the curve, stratified so the curve shows instead of the dice."""
        total = sum(weights)
        cumulative, running = [], 0.0
        for w in weights:
            running += w / total
            cumulative.append(running)
        days = []
        for i in range(count):
            target = (i + self.rng.random()) / count
            days.append(next((d for d, c in enumerate(cumulative) if c >= target), len(weights) - 1))
        self.rng.shuffle(days)
        return days

    def signup_time(self, d, tz, after=None):
        rng = self.rng
        date = self.day0 + dt.timedelta(days=d)
        for _ in range(20):
            moment = at_local(tz, date, hour_in(rng, OPEN_HOURS))
            if (after is None or moment > after) and moment < self.horizon - 20 * MIN and moment.date() >= self.day0:
                return moment
        base = after or dt.datetime.combine(date, dt.time(12), UTC)
        return min(base + rng.uniform(5, 90) * MIN, self.horizon - rng.uniform(20, 60) * MIN)

    def country(self, near=None):
        rng = self.rng
        if near is not None:
            if near in ABROAD and rng.random() < 0.12:
                return rng.choice(ABROAD[near])
            return near
        if rng.random() < 0.04:
            return None
        return rng.choices([row[0] for row in COUNTRIES], [row[1] for row in COUNTRIES])[0]

    def person(self, gender, country, d, after=None, age_near=None):
        rng = self.rng
        row = BY_CODE.get(country) if country else None
        lang = weighted(rng, row[2]) if row else "en"
        tz = weighted(rng, row[3]) if row else rng.choice(["Europe/London", "America/New_York", "Africa/Lagos"])
        pool = NAMES[row[4] if row else "us"]
        if country == "ES":
            pool = NAMES["es"]
        first = rng.choice(pool[1] if gender == "male" else pool[0] if gender == "female" else pool[0] + pool[1])
        last = rng.choice(pool[2])
        name = first if rng.random() < 0.8 else f"{first} {last[0]}."
        base = f"{ascii_slug(first)}.{ascii_slug(last)}"
        email = None
        while email is None or email in self.emails:
            suffix = "" if email is None and rng.random() < 0.3 else str(rng.randint(1, 99))
            email = f"{base}{suffix}@{rng.choice(['example.com', 'example.com', 'example.net', 'example.org'])}"
        self.emails.add(email)
        age = age_near + rng.gauss(0, 3) if age_near else rng.gauss(29, 5.5)
        age = min(52, max(19, age))
        dob = self.today - dt.timedelta(days=int(age * 365.25 + rng.randint(0, 364)))
        signup = self.signup_time(d, tz, after)
        method = weighted(rng, [("password", .52), ("google", .33), ("both", .15)])
        person = Person(len(self.people), name, email, gender, dob, country, lang, tz, signup, method=method, skill=rng.gauss(0, 6))
        person.act(signup)
        self.people.append(person)
        return person

    def build(self):
        rng = self.rng
        weights = self.day_weights()
        couples = round(self.n * 0.43)
        for d in self.spread(weights, couples):
            kind = weighted(rng, [("fm", .93), ("ff", .04), ("mm", .03)])
            first, second = {"fm": ("female", "male"), "ff": ("female", "female"), "mm": ("male", "male")}[kind]
            if kind == "fm" and rng.random() < 0.4:
                first, second = second, first
            if rng.random() < 0.02:
                first = "nonBinary"
            if rng.random() < 0.01:
                second = "preferNotToSay"
            a = self.person(first, self.country(), d)
            lag = weighted(rng, [(0, .5), (1, .2), (2, .1), (3, .07), (5, .06), (8, .04), (13, .03)])
            b = self.person(second, self.country(a.country), min(self.days - 1, d + lag), after=a.signup + 3 * MIN, age_near=(self.today - a.dob).days / 365.25)
            invite_at = a.signup + rng.uniform(1, 25) * MIN
            link = b.signup + (rng.uniform(2, 25) * MIN if rng.random() < 0.85 else rng.uniform(60, 30 * 60) * MIN)
            if link >= self.horizon - 10 * MIN:
                link = b.signup + rng.uniform(2, 8) * MIN
            if b.signup <= a.signup:
                b.signup = a.signup + rng.uniform(3, 10) * MIN
                b.acts = [b.signup]
                link = b.signup + rng.uniform(2, 8) * MIN
            if link >= self.horizon - 5 * MIN:
                self.singles += [a, b]  # too close to now to link: two people who just signed up
                continue
            invite_at = min(invite_at, link - MIN, b.signup)
            self.couples.append(Couple(len(self.couples), a, b, invite_at, link_at=link))
        for d in self.spread(weights, max(0, self.n - len(self.people))):
            p = self.person(rng.choice(["female", "male", "female", "male", "nonBinary"]), self.country(), d)
            self.singles.append(p)
        self.internal()
        for c in self.couples:
            if c.b is not None and c.link_at is not None and not c.a.internal:
                self.couple_story(c)
        self.single_stories()
        self.special_cases()
        for p in self.people:
            self.cycle_story(p)
            p.version = self.version_for(p)
        return self

    def internal(self):
        """The team's own couple and the store reviewer: real usage, left out of analytics."""
        a = Person(len(self.people), "Tobi (team)", MARKER, "male", dt.date(1993, 5, 14), "NG", "en", "Africa/Lagos",
                   at_local("Africa/Lagos", self.day0, 10.2), method="both", internal=True)
        b = Person(len(self.people) + 1, "Ada (team)", "qa.partner@heartpact.app", "female", dt.date(1995, 9, 2), "NG", "en", "Africa/Lagos",
                   a.signup + 7 * MIN, method="password", internal=True)
        reviewer = Person(len(self.people) + 2, "Play review", "play-review@heartpact.app", "preferNotToSay", dt.date(1990, 1, 1), "US", "en",
                          "America/Los_Angeles", at_local("America/Los_Angeles", self.day0 + dt.timedelta(days=3), 11), internal=True)
        for p in (a, b, reviewer):
            p.act(p.signup)
            self.people.append(p)
            self.emails.add(p.email)
        c = Couple(len(self.couples), a, b, a.signup + 2 * MIN, link_at=b.signup + 3 * MIN)
        c.segment = "power"
        self.couples.append(c)
        self.singles.append(reviewer)
        self.couple_story(c, segment="power", lifetime=10_000)

    def version_for(self, p):
        last = max(p.acts) if p.acts else p.signup
        released = [v for day, v in VERSIONS if self.day0 + dt.timedelta(days=day) <= last.date()]
        if len(released) > 1 and self.rng.random() < 0.25:
            return released[-2]
        return released[-1]

    # -- couples

    def couple_story(self, c, segment=None, lifetime=None):
        rng = random.Random(f"{self.seed}:couple:{c.index}")
        seg = segment or weighted(rng, [(name, spec["share"]) for name, spec in SEGMENTS.items()])
        spec = SEGMENTS[seg]
        c.segment = seg
        life = lifetime if lifetime is not None else rng.expovariate(1 / spec["life"])
        c.churn_at = c.link_at + dt.timedelta(days=max(0.3, life))
        self.churn[c.a.index] = self.churn[c.b.index] = c.churn_at
        if lifetime is None and c.link_at < self.horizon - 12 * DAY and rng.random() < 0.065:
            room = (self.horizon - c.link_at) / DAY - 2
            c.breakup_at = c.link_at + dt.timedelta(days=rng.uniform(7, min(55, room)), hours=rng.uniform(0, 12))
            c.breakup_by = rng.choice([c.a, c.b])
        c.end = min(c.breakup_at or self.horizon, self.horizon)
        lead, other = (c.a, c.b) if rng.random() < 0.6 else (c.b, c.a)
        engagement = {lead.index: 0.95, other.index: 0.8}
        for p in (c.a, c.b):
            p.food = rng.random() < spec["food"]
            p.scanner = p.food and rng.random() < 0.38
        c.add(c.invite_at, "invite")
        c.add(c.link_at, "link")
        self.purchase(c, rng, spec)  # first: Plus changes how many food scans a day they get

        # Which days the couple used the app, and who.
        active = {}
        date = c.link_at.date()
        while dt.datetime.combine(date, dt.time(), UTC) < c.end:
            start = dt.datetime.combine(date, dt.time(), UTC)
            age = (start - c.link_at) / DAY
            if start + DAY <= c.churn_at or date == c.link_at.date():
                chance = spec["base"] * (0.62 + 0.38 * math.exp(-age / 28))
            else:
                chance = 0.03
            if date.weekday() >= 5:
                chance *= 0.88
            if date == c.link_at.date() or rng.random() < chance:
                who = [p for p in (c.a, c.b) if rng.random() < engagement[p.index]] or [lead]
                active[date] = who
            date += DAY

        def window(p, date, weights, lo=None, hi=None):
            # Draws again only for a time before they linked; a time after now (or the breakup) is simply not used,
            # so today stays a partial day instead of squeezing everyone's evening into its morning.
            low, high = lo or c.link_at + MIN, min(hi or c.end, c.end) - MIN
            for _ in range(8):
                moment = at_local(p.tz, date, hour_in(rng, weights))
                if moment >= high:
                    return None
                if moment > low:
                    return moment
            return None

        # Opening the app, messages, meals, daily questions, games, photos.
        asked = set()
        for date, who in active.items():
            for p in who:
                for _ in range(rng.choice([1, 1, 2, 3])):
                    moment = window(p, date, OPEN_HOURS)
                    if moment:
                        p.act(moment)
                for _ in range(self.poisson(rng, spec["msgs"])):
                    moment = window(p, date, OPEN_HOURS)
                    if moment:
                        c.add(moment, "message", sender=p, text=rng.choice(MESSAGES.get(p.lang, MESSAGES["en"])))
                        p.act(moment)
                if p.food and rng.random() < 0.72:
                    self.meals(c, p, date, rng, window)
            if rng.random() < spec["questions"]:
                first = rng.choice(who)
                moment = window(first, date, EVENING)
                if moment:
                    day = local_date(first.tz, moment)
                    if day not in asked and day <= self.today:
                        asked.add(day)
                        answers = [(first, moment)]
                        other_p = c.b if first is c.a else c.a
                        if other_p in who and rng.random() < 0.8:
                            later = moment + rng.uniform(5, 300) * MIN
                            if later < c.end - MIN:
                                answers.append((other_p, later))
                        elif rng.random() < 0.3:
                            later = moment + rng.uniform(30, 900) * MIN
                            if later < c.end - MIN:
                                answers.append((other_p, later))
                        created = max(c.link_at + 30 * SEC, moment - rng.uniform(1, 120) * MIN, at_local(first.tz, day, 0.1))
                        c.add(moment, "question", day=day, created=min(created, moment - 10 * SEC), answers=answers)
                        for p, t in answers:
                            p.act(t)
            if rng.random() < spec["games"] * (1.3 if date.weekday() >= 5 else 1):
                self.game(c, rng, date, who, window)
            if c.breakup_at is None and rng.random() < spec["photos"]:
                p = rng.choice(who)
                for _ in range(rng.choice([1, 1, 2, 3, 4, 6])):
                    moment = window(p, date, OPEN_HOURS)
                    if moment:
                        video = rng.random() < 0.07
                        size = rng.randint(8_000_000, 60_000_000) if video else rng.randint(700_000, 4_800_000)
                        c.memories.append(dict(by=p, at=moment, video=video, size=size, source=weighted(rng, [("Camera", .55), ("Gallery", .4), ("GooglePhotos", .05)])))
                        p.act(moment)

        self.dares(c, rng, spec, active)
        self.location(c, rng, spec)
        if c.breakup_at:
            c.add(c.breakup_at, "breakup", by=c.breakup_by)
            c.breakup_by.act(c.breakup_at)
            for p in (c.a, c.b):
                if rng.random() < 0.4:
                    again = c.breakup_at + dt.timedelta(days=rng.uniform(0.2, 12))
                    if again < self.horizon - 10 * MIN:
                        c.add(again, "reinvite", who=p)
                        p.act(again)

    @staticmethod
    def poisson(rng, lam):
        limit, k, product = math.exp(-lam), 0, rng.random()
        while product > limit:
            k += 1
            product *= rng.random()
        return k

    def meals(self, c, p, date, rng, window):
        cuisine = BY_CODE[p.country][5] if p.country else "us"
        count = weighted(rng, [(1, .3), (2, .35), (3, .25), (4, .1)])
        kinds = ["breakfast", "lunch", "dinner", "snack"]
        chosen = sorted(rng.sample(kinds[:3], min(count, 3)) + (["snack"] if count == 4 else []), key=kinds.index)
        scans_today = 0
        for meal in chosen:
            lo, hi = MEAL_HOURS[meal]
            moment = at_local(p.tz, date, rng.uniform(lo, hi))
            if not (c.link_at < moment < c.end - MIN):
                continue
            options = [m for m in MEALS[cuisine] if m[1] == meal] or MEALS[cuisine]
            description, _, kcal = rng.choice(options)
            kcal = int(kcal * rng.uniform(0.8, 1.2))
            scan = None
            limit = PLUS_SCANS if self.plus_at(c, moment) else FREE_SCANS
            if p.scanner and scans_today < limit and rng.random() < 0.6:
                scans_today += 1
                failed = weighted(rng, [(None, .93), ("food.not_food", .04), ("food.scan_error", .03)])
                scan = dict(failed=failed, at=moment - rng.uniform(0.5, 3) * MIN, bytes=rng.randint(160_000, 950_000),
                            input=rng.randint(1500, 2700), output=0 if failed == "food.scan_error" else rng.randint(240, 480),
                            latency=rng.randint(2400, 9500), confidence=round(rng.uniform(0.55, 0.92), 2))
                if failed:
                    scan["output"] = rng.randint(20, 60) if failed == "food.not_food" else 0
            if p.target_set is None and rng.random() < 0.65:
                p.target_set = moment - 2 * MIN
                c.add(p.target_set, "targets", who=p, calories=rng.choice([1600, 1800, 2000, 2200, 2400, 2600]),
                      share=weighted(rng, [("totalsOnly", .55), ("fullDetail", .3), ("none", .15)]))
            c.add(moment, "meal", who=p, meal=meal, description=description, calories=kcal, scan=scan)
            p.act(moment)

    def plus_at(self, c, moment):
        return c.plus_from is not None and c.plus_from <= moment < (c.plus_until or FAR)

    def game(self, c, rng, date, who, window):
        kind = weighted(rng, GAMES)
        inviter = rng.choice(who)
        partner = c.b if inviter is c.a else c.a
        start = window(inviter, date, EVENING)
        if start is None:
            return
        fate = "played" if kind in PLAYED else "abandoned"
        roll = rng.random()
        if roll < 0.08:
            fate = "declined"
        elif kind in PLAYED and roll < 0.16:
            fate = "abandoned"
        lag = rng.uniform(0.3, 5) if rng.random() < 0.65 else rng.uniform(5, 90)
        accepted = start + lag * MIN
        minutes = {"skillDuel": rng.uniform(4, 40) if rng.random() < 0.8 else rng.uniform(60, 600), "ticTacToe": rng.uniform(2, 7),
                   "connectFour": rng.uniform(3, 12), "wouldYouRather": rng.uniform(3, 9), "truthOrDare": rng.uniform(8, 25),
                   "coupleQuiz": rng.uniform(5, 15)}.get(kind, rng.uniform(1, 6))
        if fate == "abandoned":
            minutes *= rng.uniform(0.15, 0.5)
        ended = start + rng.uniform(2, 20) * 60 * MIN if fate == "declined" else accepted + minutes * MIN
        if ended >= c.end - MIN:
            return
        c.add(start, "game", kind=kind, inviter=inviter, partner=partner, fate=fate, start=start, accepted=accepted, ended=ended,
              moves=rng.randint(1, 6), seed=rng.random())
        inviter.act(start)
        if fate != "declined":
            partner.act(accepted)
            inviter.act(ended)
            partner.act(ended)

    def dares(self, c, rng, spec, active):
        if rng.random() >= spec["goals"]:
            return
        count = 1
        if c.segment == "power":
            count = weighted(rng, [(1, .25), (2, .6), (3, .15)])
        elif c.segment == "regular":
            count = weighted(rng, [(1, .7), (2, .3)])
        for n in range(count):
            created = c.link_at + dt.timedelta(days=rng.uniform(0.01, 2.5) if n == 0 else rng.uniform(1, 14))
            if created >= c.end - 10 * MIN:
                continue
            creator = rng.choice([c.a, c.b])
            assignee = (c.b if creator is c.a else c.a) if rng.random() < 0.7 else creator
            exercise = weighted(rng, list(EXERCISE_WEIGHTS.items()))
            per_unit, targets, measure = EXERCISES[exercise]
            target = rng.choice(targets)
            slot = weighted(rng, [("morning", .45), ("midday", .12), ("evening", .43)])
            hours = {"morning": rng.uniform(6, 8.6), "midday": rng.uniform(12, 13.6), "evening": rng.uniform(17.5, 21.6)}[slot]
            hhmm = (int(hours), rng.choice([0, 0, 0, 15, 30, 30, 45]))
            grace = weighted(rng, [(30, .6), (15, .15), (60, .2), (120, .05)])
            key = f"g{n}"
            c.add(created, "goal", key=key, creator=creator, assignee=assignee, exercise=exercise, target=target, hhmm=hhmm, grace=grace)
            creator.act(created)
            # When they stop it: soon after they drift away, much later, or never.
            stop = None
            if c.churn_at < c.end:
                stop = c.churn_at + dt.timedelta(days=weighted(rng, [(rng.uniform(0, 4), .6), (rng.uniform(3, 12), .32), (400, .08)]))
            tz = assignee.tz
            partner = c.b if assignee is c.a else c.a
            day = local_date(tz, created)
            while True:
                scheduled = at_local(tz, day, hhmm[0] + hhmm[1] / 60)
                day += DAY
                if scheduled < created:
                    continue
                if scheduled >= c.end or (stop and scheduled >= stop) or scheduled >= self.horizon:
                    break
                occurrence = self.occurrence(rng, spec, active, assignee, partner, scheduled, grace, exercise, target, per_unit, measure)
                if occurrence is None or occurrence["end"] >= self.horizon - MIN or occurrence["end"] >= c.end - 30 * SEC:
                    break  # its window is still open: the server's own alarm takes it from here
                occurrence["day"] = local_date(tz, scheduled)
                c.add(scheduled, "dare", key=key, **occurrence)
            if stop and stop < c.end and stop < self.horizon - 10 * MIN:
                stopper = rng.choice([c.a, c.b])
                c.add(stop, "goal_stop", key=key, who=stopper)
                stopper.act(stop)

    def churn_of(self, person):
        return self.churn.get(person.index, FAR)

    def occurrence(self, rng, spec, active, assignee, partner, scheduled, grace, exercise, target, per_unit, measure):
        date = scheduled.date()
        engaged = assignee in active.get(date, [])
        living = scheduled < self.churn_of(assignee)
        # The alarm itself brings people back: while the couple is still into it, most dares get done.
        chance = spec["complete"] * (1.0 if engaged else 0.85) if living else 0.08
        if scheduled.weekday() >= 5:
            chance *= 0.9
        deadline = scheduled + grace * MIN
        roll = rng.random()
        result = dict(scheduled=scheduled, deadline=deadline, grace=grace, partner=partner, assignee=assignee)
        if roll < chance:
            if rng.random() < 0.1 and scheduled.astimezone(zone(assignee.tz)).hour >= 7:
                started = scheduled - rng.uniform(4, 80) * MIN
                alarm = started
                early = True
            else:
                alarm = scheduled + rng.uniform(0.2, 2.5) * SEC
                delay = min(rng.lognormvariate(math.log(150), 0.9), grace * 60 - 25)
                started = scheduled + max(5, delay) * SEC
                early = False
            minimum = per_unit * target
            if measure == "seconds":
                duration = target + rng.uniform(6, 30)
            else:
                duration = minimum * rng.uniform(1.15, 2.1) + rng.uniform(8, 35)
            complete_by = min(max(started + 10 * MIN, deadline), deadline + 10 * MIN)
            completed = started + duration * SEC
            if completed > complete_by - 5 * SEC:
                completed = complete_by - 5 * SEC
                started = completed - max(minimum + 3, duration * 0.9) * SEC
            checks = dict(formFaults=0 if rng.random() < 0.9 else rng.randint(1, 4), fastReps=0 if rng.random() < 0.95 else rng.randint(1, 3),
                          challengesPassed=0 if rng.random() < 0.6 else rng.randint(1, 2), challengesMissed=0 if rng.random() < 0.97 else 1,
                          phoneMovedReps=0 if rng.random() < 0.98 else rng.randint(1, 2), outOfFrameSeconds=0 if rng.random() < 0.95 else rng.randint(1, 6))
            result.update(outcome="completed", alarm=alarm, started=started, early=early, completed=completed, complete_by=complete_by,
                          achieved=target + (rng.randint(1, 3) if rng.random() < 0.15 and measure == "reps" else 0),
                          confidence=round(min(0.99, max(0.56, rng.gauss(0.86, 0.07))), 3), checks=checks, end=completed,
                          duration=(completed - started).total_seconds())
            assignee.act(started)
            assignee.act(completed)
        elif roll < chance + (0.06 if engaged else 0.04):
            alarm = scheduled + rng.uniform(0.2, 2.5) * SEC
            asked = alarm + rng.uniform(0.5, min(grace, 20)) * MIN if rng.random() < 0.55 else None
            cancel = (asked or alarm) + rng.uniform(0.5, 15) * MIN
            cancel = min(cancel, deadline - 5 * SEC)
            if asked and asked >= cancel:
                asked = None
            result.update(outcome="cancelled", alarm=alarm, asked=asked, cancelled=cancel, end=cancel)
            if asked:
                assignee.act(asked)
            partner.act(cancel)
        else:
            alarm = scheduled + rng.uniform(0.2, 2.5) * SEC
            started = None
            complete_by = None
            end = deadline + rng.uniform(0.3, 1.6) * SEC
            if engaged and rng.random() < 0.3:
                started = scheduled + rng.uniform(1, max(1.5, grace - 1)) * MIN
                complete_by = min(max(started + 10 * MIN, deadline), deadline + 10 * MIN)
                end = complete_by + rng.uniform(0.3, 1.6) * SEC
                assignee.act(started)
            result.update(outcome="missed", alarm=alarm, started=started, complete_by=complete_by, end=end)
        return result

    def location(self, c, rng, spec):
        if rng.random() >= spec["loc"]:
            return
        first = rng.choice([c.a, c.b])
        sharers = [first] + ([c.b if first is c.a else c.a] if rng.random() < 0.6 else [])
        for p in sharers:
            start = c.link_at + dt.timedelta(days=rng.uniform(0.2, 15))
            if start >= c.end - 10 * MIN:
                continue
            c.add(start, "loc_start", who=p)
            p.act(start)
            if rng.random() < 0.55:
                stop = start + dt.timedelta(days=rng.uniform(0.05, 20))
                if stop < c.end - 10 * MIN:
                    c.add(stop, "loc_stop", who=p)
                    p.act(stop)

    def purchase(self, c, rng, spec):
        if rng.random() >= spec["buy"]:
            return
        bought = c.link_at + dt.timedelta(days=max(0.3, rng.gammavariate(1.6, 7)))
        if bought >= min(c.end, self.horizon) - 30 * MIN or bought > c.churn_at + 3 * DAY:
            return
        buyer = c.a if rng.random() < 0.6 else c.b
        product = "heartpact_plus_monthly" if rng.random() < 0.72 else "heartpact_plus_yearly"
        period = dt.timedelta(days=PRODUCTS[product])
        gone = min(c.churn_at, c.breakup_at or FAR)
        state = "active"
        if gone < self.horizon:
            cancel_at = gone + dt.timedelta(days=rng.uniform(0, 20))
            if cancel_at < self.horizon:
                periods = max(1, math.ceil((cancel_at - bought) / period))
                expires = bought + periods * period
                state = "expired" if expires < self.horizon else "canceled"
            else:
                expires = bought + max(1, math.ceil((self.horizon - bought) / period)) * period
        else:
            expires = bought + max(1, math.ceil((self.horizon - bought) / period)) * period
        if state == "active" and product.endswith("monthly"):
            renewed = expires - period  # the latest renewal
            if bought < renewed and self.horizon - renewed < 4 * DAY and rng.random() < 0.6:
                state, expires = "grace", self.horizon + dt.timedelta(days=rng.uniform(1, 3))
            elif bought < renewed and rng.random() < 0.04:
                state, expires = "onhold", self.horizon - dt.timedelta(days=rng.uniform(2, 9))
        c.plus_from = bought
        c.plus_until = expires if state in ("active", "canceled", "grace") else min(expires, self.horizon)
        c.add(bought, "purchase", buyer=buyer, product=product, state=state, expires=expires)
        buyer.act(bought)

    def pregnancy(self, c, rng):
        owner = next((p for p in (c.a, c.b) if p.gender == "female"), None)
        if owner is None or c.segment == "casual" or c.breakup_at or c.link_at > self.horizon - 10 * DAY:
            return
        start = c.link_at + dt.timedelta(days=rng.uniform(3, 30))
        if start >= self.horizon - 30 * MIN:
            return
        c.add(start, "pregnancy", who=owner, due=self.today + dt.timedelta(days=rng.randint(40, 230)))
        owner.act(start)

    def cycle_story(self, p):
        """Women own a cycle from sign-up; about half set it up, some log their days."""
        rng = random.Random(f"{self.seed}:cycle:{p.index}")
        if p.gender != "female" or rng.random() > 0.58:
            return
        set_at = p.signup + dt.timedelta(days=rng.uniform(0.02, 4))
        if set_at >= self.horizon - 10 * MIN:
            return
        length = rng.randint(25, 32)
        period = rng.randint(4, 6)
        last_start = local_date(p.tz, set_at) - dt.timedelta(days=rng.randint(2, length - 1))
        logs = []
        last_active = max(p.acts) if p.acts else p.signup
        if rng.random() < 0.65:
            start = last_start
            while start <= self.today:
                for offset in range(period):
                    day = start + dt.timedelta(days=offset)
                    when = at_local(p.tz, day, rng.uniform(19, 23.5))
                    if set_at < when < min(self.horizon, last_active + 2 * DAY) and rng.random() < 0.75:
                        flow = ["heavy", "medium", "medium", "light", "light", "spotting"][min(offset, 5)]
                        logs.append((day, when, flow))
                start += dt.timedelta(days=length + rng.randint(-2, 2))
        p.cycle = dict(at=set_at, length=length, period=period, last_start=last_start,
                       share=weighted(rng, [("none", .3), ("predictionsOnly", .45), ("fullDetail", .25)]), logs=logs)
        p.act(set_at)
        for _, when, _ in logs:
            p.act(when)

    # -- singles and the odd ones out

    def single_stories(self):
        for p in self.singles:
            rng = random.Random(f"{self.seed}:single:{p.index}")
            invite = p.internal or rng.random() < 0.6
            c = Couple(len(self.couples), p, None, p.signup + rng.uniform(1, 30) * MIN)
            c.end = self.horizon
            if invite and c.invite_at < self.horizon:
                c.add(c.invite_at, "invite")
                p.act(c.invite_at)
            for _ in range(rng.choice([0, 0, 1, 1, 2, 3])):
                moment = p.signup + dt.timedelta(days=rng.uniform(0.1, 9))
                if moment < self.horizon:
                    p.act(moment)
            self.couples.append(c)

    def special_cases(self):
        rng = self.rng
        normal_singles = [p for p in self.singles if not p.internal and p.signup < self.horizon - 7 * DAY]
        rng.shuffle(normal_singles)
        for p in normal_singles[:2]:
            p.delete_at = p.signup + dt.timedelta(days=rng.uniform(0.2, 5))
            p.act(p.delete_at)
        spammer = normal_singles[2] if len(normal_singles) > 2 else None
        if spammer:
            spammer.name = "Promo deals 💸"
            spammer.disable_at = spammer.signup + dt.timedelta(days=rng.uniform(0.5, 3))
        exes = [c for c in self.couples if c.breakup_at and c.b]
        if exes:
            c = exes[0]
            p = c.b if c.breakup_by is c.a else c.a
            p.delete_at = c.breakup_at + dt.timedelta(days=rng.uniform(0.5, 4))
            if p.delete_at < self.horizon - 10 * MIN:
                p.act(p.delete_at)
                c.events = [event for event in c.events if not (event[2] == "reinvite" and event[3]["who"] is p)]
            else:
                p.delete_at = None
        # Complimentary Plus for a few couples (creators, support), given by the admin.
        candidates = [c for c in self.couples if c.b and c.link_at and not c.breakup_at and c.plus_from is None and not c.a.internal
                      and c.segment != "casual" and c.link_at < self.horizon - 20 * DAY]
        rng.shuffle(candidates)
        reasons = ["Creator partnership", "Support: alarms didn't ring for a week", "Beta tester thank-you"]
        for c, days, reason in zip(candidates[:3], [90, 60, 14], reasons):
            given = c.link_at + dt.timedelta(days=rng.uniform(2, 15))
            if days == 14:
                given = max(given, self.horizon - 40 * DAY)
            c.grant = dict(at=given, days=days, reason=reason)
        # Three pregnancies.
        hopeful = [c for c in self.couples if c.b and c.link_at and not c.a.internal]
        rng.shuffle(hopeful)
        for c in hopeful:
            if sum(1 for x in self.couples for e in x.events if e[2] == "pregnancy") >= 3:
                break
            self.pregnancy(c, rng)
        # A couple of free albums close to their limit.
        heavy = [c for c in self.couples if c.memories and c.plus_from is None and c.grant is None]
        for c in sorted(heavy, key=lambda c: -len(c.memories))[:2]:
            total = sum(m["size"] for m in c.memories)
            scale = 470_000_000 / max(total, 1)
            for m in c.memories:
                m["size"] = int(m["size"] * scale)

    def summary(self):
        kinds = collections.Counter(event[2] for c in self.couples for event in c.events)
        outcomes = collections.Counter(event[3]["outcome"] for c in self.couples for event in c.events if event[2] == "dare")
        return dict(people=len(self.people), couples=sum(1 for c in self.couples if c.b), singles=sum(1 for c in self.couples if not c.b),
                    breakups=sum(1 for c in self.couples if c.breakup_at), segments=collections.Counter(c.segment for c in self.couples if c.b),
                    events=dict(kinds), dares=dict(outcomes), photos=sum(len(c.memories) for c in self.couples),
                    countries=collections.Counter(p.country for p in self.people).most_common(8),
                    signups_by_week=self.weekly([p.signup for p in self.people]),
                    active_by_week=self.weekly([(p.index, a.date()) for p in self.people for a in p.acts], distinct=True))

    def weekly(self, items, distinct=False):
        weeks = collections.Counter()
        for item in set(items) if distinct else items:
            day = item[1] if distinct else item.date()
            weeks[(day - self.day0).days // 7] += 1
        return [weeks[w] for w in range(math.ceil(self.days / 7))]


# ---------------------------------------------------------------------------------------------------------------------
# Running the plan through the API


class Run:
    def __init__(self, plan):
        self.plan = plan
        self.started = None  # the database's clock when the run began: rows created after it still need moving

    # -- small helpers

    def api(self, method, path, body=None, who=None, what=None, headers=None):
        status, data = call(method, path, body, token=who.token if who else None, lang=who.accept_language if who else None, headers=headers)
        if not ok(status):
            STATS.add(f"failed.{what or path.split('/')[2]}.{status}")
        return status, data

    def notes(self, couple_id, at, read=0.8):
        """Notifications the last call wrote for this couple get the time the thing happened."""
        if couple_id is None:
            return
        sql(f"""UPDATE notifications SET "CreatedAtUtc" = {q(at)}, "UpdatedAtUtc" = {q(at)},
                  "ReadAtUtc" = CASE WHEN random() < {read} THEN LEAST({q(at)} + random() * interval '6 hours', {q(self.plan.horizon)}) END
                WHERE "CoupleId" = {qu(couple_id)} AND "CreatedAtUtc" >= {q(self.started)}""")

    # -- people

    def register(self, p):
        body = {"email": p.email, "password": PASSWORD, "displayName": p.name, "dateOfBirth": p.dob.isoformat(), "gender": p.gender}
        if p.country:
            body["countryCode"] = p.country
        status, data = call("POST", "/api/auth/register", body, lang=p.accept_language)
        if status == 409:
            status, data = call("POST", "/api/auth/login", {"email": p.email, "password": PASSWORD}, lang=p.accept_language)
        if not ok(status):
            raise RuntimeError(f"Could not register {p.email}: {status} {data}")
        p.id, p.token, p.couple0 = data["user"]["id"], data["accessToken"], data["user"]["coupleId"]
        prefs = {"messages": True, "games": True, "exercise": True, "cycle": True, "pregnancy": True, "dailyQuestions": True,
                 "quietHoursEnabled": False, "quietStart": None, "quietEnd": None, "timeZoneId": p.tz, "album": True}
        self.api("PUT", "/api/notification/preferences", prefs, p, "preferences")
        self.api("PUT", "/api/devices", {"token": "demo-" + uuid.uuid4().hex + uuid.uuid4().hex, "platform": "android",
                                         "appVersion": p.version, "tokenKind": "registrationToken"}, p, "devices")
        STATS.add("people")

    # -- one couple, start to finish, in order

    def couple(self, c):
        handlers = {"invite": self.e_invite, "link": self.e_link, "message": self.e_message, "meal": self.e_meal, "targets": self.e_targets,
                    "question": self.e_question, "game": self.e_game, "goal": self.e_goal, "dare": self.e_dare, "goal_stop": self.e_goal_stop,
                    "loc_start": self.e_loc_start, "loc_stop": self.e_loc_stop, "purchase": self.e_purchase, "pregnancy": self.e_pregnancy,
                    "breakup": self.e_breakup, "reinvite": self.e_reinvite}
        c.id = c.a.couple0
        for moment, _, kind, data in sorted(c.events, key=lambda event: (event[0], event[1])):
            try:
                handlers[kind](c, moment, **data)
            except Exception as error:  # one bad event must not lose the couple's whole story
                STATS.add(f"error.{kind}")
                if STATS.counts[f"error.{kind}"] <= 3:
                    print(f"  ! {kind} for couple {c.index}: {error}", file=sys.stderr)
        self.flush_stamps(c)
        for p in (c.a, c.b):
            if p is not None and p.delete_at and not p.deleted:
                self.delete(p)

    def flush_stamps(self, c):
        for table, column_sets, rows in (("food_entries", ('"LoggedAtUtc"', '"CreatedAtUtc"', '"UpdatedAtUtc"'), c.stamps_food),
                                         ("messages", ('"CreatedAtUtc"', '"UpdatedAtUtc"'), c.stamps_messages)):
            for chunk in range(0, len(rows), 500):
                part = rows[chunk:chunk + 500]
                sets = ", ".join(f"{column} = v.t" for column in column_sets)
                sql(f"""UPDATE {table} AS x SET {sets} FROM (VALUES {values([(qu(i), q(t)) for i, t in part])}) AS v(id, t) WHERE x."Id" = v.id""")
        c.stamps_food.clear()
        c.stamps_messages.clear()

    def e_invite(self, c, at):
        status, invite = self.api("POST", "/api/couple/invites", {}, c.a, "invite")
        if not ok(status):
            return
        c.invite = invite
        if c.b is None:
            self.stamp_invite(invite["id"], at, at)

    def stamp_invite(self, invite_id, created, updated):
        sql(f"""UPDATE partner_invites SET "CreatedAtUtc" = {q(created)}, "UpdatedAtUtc" = {q(updated)}, "ExpiresAtUtc" = {q(created + INVITE_LIFETIME)}
                WHERE "Id" = {qu(invite_id)}""")

    def e_link(self, c, at):
        status, data = self.api("POST", "/api/couple/invites/accept", {"code": c.invite["code"]}, c.b, "accept")
        if not ok(status):
            raise RuntimeError(f"accept failed: {status} {data}")
        c.b.token = data["accessToken"]
        c.id = data["user"]["coupleId"]
        c.linked = True
        self.stamp_invite(c.invite["id"], c.invite_at, at)
        self.notes(c.id, at)

    def e_message(self, c, at, sender, text):
        status, message = self.api("POST", "/api/message", {"body": text}, sender, "message")
        if ok(status):
            c.stamps_messages.append((message["id"], at))
            self.notes(c.id, at, read=0.92)

    def e_targets(self, c, at, who, calories, share):
        protein, carbs, fat = round(calories * 0.25 / 4), round(calories * 0.5 / 4), round(calories * 0.25 / 9)
        body = {"dailyCalories": calories, "proteinGrams": protein, "carbsGrams": carbs, "fatGrams": fat, "shareLevel": share}
        status, _ = self.api("PUT", "/api/food/targets", body, who, "targets")
        if ok(status):
            sql(f"""UPDATE nutrition_targets SET "CreatedAtUtc" = {q(at)}, "UpdatedAtUtc" = {q(at)} WHERE "UserId" = {qu(who.id)}""")

    def e_meal(self, c, at, who, meal, description, calories, scan):
        rng = random.Random(f"{at.timestamp()}:{who.index}")
        split = (rng.uniform(.15, .3), rng.uniform(.4, .55))
        macros = dict(proteinGrams=round(calories * split[0] / 4, 1), carbsGrams=round(calories * split[1] / 4, 1),
                      fatGrams=round(calories * (1 - split[0] - split[1]) / 9, 1))
        body = {"localDate": local_date(who.tz, at).isoformat(), "mealType": meal, "description": description, "calories": calories, **macros}
        if scan:
            scan_id = str(uuid.uuid4())
            low, high = int(calories * 0.82), int(calories * 1.2)
            result = None
            if not scan["failed"]:
                result = json.dumps({"description": description, "calories": calories, "caloriesLow": low, "caloriesHigh": high, **macros,
                                     "confidence": scan["confidence"], "items": [{"name": description, "portion": "1 plate", "calories": calories}]})
            sql(f"""INSERT INTO food_scans ("Id", "UserId", "CoupleId", "Model", "MediaType", "ImageBytes", "Succeeded", "ErrorCode", "InputTokens",
                      "OutputTokens", "LatencyMs", "ResultJson", "CreatedAtUtc", "UpdatedAtUtc")
                    VALUES ({qu(scan_id)}, {qu(who.id)}, {qu(c.id)}, 'fake-estimator', 'image/jpeg', {scan['bytes']}, {q(not scan['failed'])},
                      {q(scan['failed'])}, {scan['input']}, {scan['output']}, {scan['latency']}, {q(result)}{'::jsonb' if result else ''},
                      {q(scan['at'])}, {q(scan['at'] + scan['latency'] / 1000 * SEC)})""")
            STATS.add("sql.food_scans")
            if not scan["failed"]:
                body["scanId"] = scan_id
        status, entry = self.api("POST", "/api/food/entries", body, who, "meal")
        if ok(status):
            c.stamps_food.append((entry["id"], at))

    def e_question(self, c, at, day, created, answers):
        first = answers[0][0]
        status, question = self.api("GET", "/api/questions/today", None, first, "question")
        if not ok(status):
            return
        qid = question["id"]
        fresh = sql(f"""SELECT "CreatedAtUtc" >= {q(self.started)} FROM daily_questions WHERE "Id" = {qu(qid)}""")
        if not fresh or fresh[0][0] != "t":
            STATS.add("skipped.question_taken")  # today's question already went to an earlier day's event (two time zones)
            return
        utc_today = dt.datetime.now(UTC).date()
        if day > utc_today:
            STATS.add("skipped.question_tomorrow")
            return
        if day != utc_today:
            existing = sql(f"""SELECT 1 FROM daily_questions WHERE "CoupleId" = {qu(c.id)} AND "LocalDate" = {q(day)} AND "Id" <> {qu(qid)}""")
            if existing:
                return
        rng = random.Random(f"q:{qid}")
        for person, moment in answers:
            status, _ = self.api("PUT", f"/api/questions/{qid}/answer", {"body": rng.choice(ANSWERS)}, person, "answer")
            if ok(status):
                sql(f"""UPDATE daily_answers SET "CreatedAtUtc" = {q(moment)}, "UpdatedAtUtc" = {q(moment)}
                        WHERE "DailyQuestionId" = {qu(qid)} AND "UserId" = {qu(person.id)}""")
                self.notes(c.id, moment)
        sql(f"""UPDATE daily_questions SET "LocalDate" = {q(day)}, "CreatedAtUtc" = {q(created)}, "UpdatedAtUtc" = {q(created)} WHERE "Id" = {qu(qid)}""")

    # Games: invited, accepted and played move by move; the countdown is skipped with SQL.

    def e_game(self, c, at, kind, inviter, partner, fate, start, accepted, ended, moves, seed):
        status, session = call("POST", "/api/game/sessions", {"gameType": kind}, token=inviter.token, lang=inviter.accept_language)
        if not ok(status) and status != 500:
            STATS.add(f"failed.game.{status}")
        if status == 500:
            # App bug: the invite is saved, but describing a game that hasn't started throws for Heart to heart, Truth
            # or dare and Draw and guess (their ToPublicState on an empty state), so the API answers 500. Find the
            # saved invite instead.
            found = sql(f"""SELECT "Id" FROM game_sessions WHERE "CoupleId" = {qu(c.id)} AND "Status" = 'WaitingForPartner'
                            AND "CreatedAtUtc" >= {q(self.started)} ORDER BY "CreatedAtUtc" DESC LIMIT 1""")
            if not found:
                return
            STATS.add(f"app_bug.invite_500.{kind}")
            session = {"id": found[0][0]}
        elif not ok(status):
            return
        sid = session["id"]
        self.notes(c.id, start)
        if fate == "declined":
            status, _ = call("POST", f"/api/game/sessions/{sid}/abandon", {}, token=inviter.token, lang=inviter.accept_language)
            if status == 500:
                STATS.add(f"app_bug.abandon_invite_500.{kind}")  # the same bug; the game is abandoned all the same
            elif not ok(status):
                STATS.add(f"failed.game_abandon.{status}")
        else:
            status, session = self.api("POST", f"/api/game/sessions/{sid}/accept", {}, partner, "game_accept")
            if not ok(status):
                return
            sql(f"""UPDATE game_sessions SET "CountdownEndsAtUtc" = now() - interval '1 second' WHERE "Id" = {qu(sid)}""")
            rng = random.Random(seed)
            players = {inviter.id: inviter, partner.id: partner}
            limit = moves if fate == "abandoned" else 400
            played = 0
            plan = Moves(kind, list(players.values()), rng)
            while session["status"] == "inProgress" and played < limit:
                move = plan.next(session, players)
                if move is None:
                    break
                actor, action, value, index = move
                status, after = self.api("POST", f"/api/game/sessions/{sid}/actions",
                                         {"expectedStateVersion": session["stateVersion"], "action": action, "value": value, "index": index},
                                         actor, f"game_move_{kind}")
                if not ok(status):
                    break
                session = after
                played += 1
            if session["status"] == "inProgress":
                quitter = rng.choice([inviter, partner])
                self.api("POST", f"/api/game/sessions/{sid}/abandon", {}, quitter, "game_abandon")
            else:
                STATS.add(f"games.{kind}")
        sql(f"""UPDATE game_sessions SET "CreatedAtUtc" = {q(start)},
                  "InviteAcceptedAtUtc" = CASE WHEN "InviteAcceptedAtUtc" IS NULL THEN NULL ELSE {q(accepted)} END,
                  "CountdownEndsAtUtc" = CASE WHEN "CountdownEndsAtUtc" IS NULL THEN NULL ELSE {q(accepted + 5 * SEC)} END,
                  "StartedAtUtc" = CASE WHEN "StartedAtUtc" IS NULL THEN NULL ELSE {q(accepted)} END,
                  "EndedAtUtc" = CASE WHEN "EndedAtUtc" IS NULL THEN NULL ELSE {q(ended)} END,
                  "UpdatedAtUtc" = {q(ended)} WHERE "Id" = {qu(sid)}""")
        self.notes(c.id, ended)

    # Dares. Each occurrence is opened today with start-early, then moved to its own day.

    def e_goal(self, c, at, key, creator, assignee, exercise, target, hhmm, grace):
        body = {"assignedUserId": assignee.id, "exerciseType": exercise, "title": None, "target": target,
                "scheduledTime": f"{hhmm[0]:02d}:{hhmm[1]:02d}", "timeZoneId": assignee.tz, "graceMinutes": grace}
        status, goal = self.api("POST", "/api/exercise/goals", body, creator, "goal")
        if not ok(status):
            return
        sql(f"""UPDATE exercise_goals SET "NextDueAtUtc" = {q(FAR)} WHERE "Id" = {qu(goal['id'])}""")
        c.goals[key] = dict(id=goal["id"], tz=assignee.tz, hhmm=hhmm, grace=grace, created=at, updated=at, active=True, last=None,
                            assignee=assignee, exercise=exercise, target=target)
        self.notes(c.id, at)

    def e_goal_stop(self, c, at, key, who):
        goal = c.goals.get(key)
        if not goal or not goal["active"]:
            return
        status, _ = self.api("DELETE", f"/api/exercise/goals/{goal['id']}", None, who, "goal_stop")
        if ok(status):
            goal.update(active=False, updated=at)
            self.notes(c.id, at)

    def e_dare(self, c, at, key, outcome, day, scheduled, deadline, grace, partner, assignee, **o):
        goal = c.goals.get(key)
        if not goal or not goal["active"]:
            return
        now = dt.datetime.now(UTC)
        due = now + 90 * SEC
        if local_date(goal["tz"], due) != local_date(goal["tz"], now) or local_date(goal["tz"], now + 5 * MIN) != local_date(goal["tz"], now):
            time.sleep(6 * 60)  # a minute or two from the goal's local midnight: wait for the new day
            now = dt.datetime.now(UTC)
            due = now + 90 * SEC
        sql(f"""UPDATE exercise_goals SET "NextDueAtUtc" = {q(due)} WHERE "Id" = {qu(goal['id'])}""")
        status, occurrence = self.api("POST", f"/api/exercise/goals/{goal['id']}/start-early", {}, assignee, "dare_start")
        if not ok(status):
            sql(f"""UPDATE exercise_goals SET "NextDueAtUtc" = {q(FAR)} WHERE "Id" = {qu(goal['id'])}""")
            return
        oid = occurrence["id"]
        if outcome == "completed":
            sql(f"""UPDATE exercise_occurrences SET "StartedAtUtc" = "StartedAtUtc" - interval '{int(o['duration']) + 2} seconds' WHERE "Id" = {qu(oid)}""")
            status, _ = self.api("POST", f"/api/exercise/occurrences/{oid}/complete",
                                 {"achievedCount": o["achieved"], "confidence": o["confidence"], "checks": o["checks"]}, assignee, "dare_complete")
            if not ok(status):
                outcome = "missed"
                o = dict(o, started=None, complete_by=None, end=deadline + SEC)
        if outcome == "cancelled":
            if o.get("asked"):
                self.api("POST", f"/api/exercise/occurrences/{oid}/ask-off", {"note": None}, assignee, "dare_ask_off")
            status, _ = self.api("POST", f"/api/exercise/occurrences/{oid}/cancel", {}, partner, "dare_cancel")
            if not ok(status):
                outcome = "missed"
                o = dict(o, started=None, complete_by=None, end=deadline + SEC)
        if outcome == "missed":
            # The server's alarm worker misses it, as it would have at the deadline.
            sql(f"""UPDATE exercise_occurrences SET "CompleteByUtc" = now() - interval '1 second' WHERE "Id" = {qu(oid)} AND "Status" = 'InProgress'""")
            for _ in range(40):
                if sql(f"""SELECT "Status" FROM exercise_occurrences WHERE "Id" = {qu(oid)}""")[0][0] == "Missed":
                    break
                time.sleep(0.25)
            else:
                STATS.add("fallback.dare_missed_by_sql")
                sql(f"""UPDATE exercise_occurrences SET "Status" = 'Missed' WHERE "Id" = {qu(oid)}""")
        # Move it to its own day, with the times it would have had.
        alarm = o["alarm"]
        started = o.get("started")
        cols = {"LocalDate": q(day), "ScheduledAtUtc": q(scheduled), "DeadlineUtc": q(deadline), "AlarmStartedAtUtc": q(alarm),
                "CreatedAtUtc": q(alarm), "UpdatedAtUtc": q(o["end"])}
        if outcome == "completed":
            cols.update(StartedAtUtc=q(started), CompleteByUtc=q(o["complete_by"]), CompletedAtUtc=q(o["completed"]), StateVersion="2")
        elif outcome == "cancelled":
            cols.update(StartedAtUtc="NULL", CompleteByUtc="NULL", StateVersion="1", CallOffRequestedAtUtc=q(o.get("asked")))
        else:
            cols.update(StartedAtUtc=q(started), CompleteByUtc=q(o.get("complete_by")), StateVersion="2" if started else "1")
        assignments = ", ".join(f'"{column}" = {value}' for column, value in cols.items())
        sql(f"""UPDATE exercise_occurrences SET {assignments} WHERE "Id" = {qu(oid)};
                UPDATE exercise_goals SET "NextDueAtUtc" = {q(FAR)} WHERE "Id" = {qu(goal['id'])}""")
        goal["last"] = scheduled
        STATS.add(f"dares.{outcome}")
        if outcome == "missed":
            sql(f"""UPDATE notifications SET "CreatedAtUtc" = {q(o['end'])}, "UpdatedAtUtc" = {q(o['end'])},
                      "ReadAtUtc" = CASE WHEN random() < 0.5 THEN LEAST({q(o['end'])} + random() * interval '10 hours', {q(self.plan.horizon)}) END
                    WHERE "CoupleId" = {qu(c.id)} AND "CreatedAtUtc" >= {q(self.started)}""")
        else:
            self.notes(c.id, o["end"])

    # Location, purchases, pregnancy, breakups.

    def e_loc_start(self, c, at, who):
        status, _ = self.api("POST", "/api/location/start", {}, who, "location")
        if ok(status):
            sql(f"""UPDATE couple_locations SET "CreatedAtUtc" = CASE WHEN "CreatedAtUtc" >= {q(self.started)} THEN {q(at)} ELSE "CreatedAtUtc" END,
                      "UpdatedAtUtc" = {q(at)}, "LastSeenAtUtc" = {q(at)} WHERE "CoupleId" = {qu(c.id)} AND "UserId" = {qu(who.id)}""")
            self.notes(c.id, at)

    def e_loc_stop(self, c, at, who):
        status, _ = self.api("POST", "/api/location/stop", {}, who, "location_stop")
        if ok(status):
            sql(f"""UPDATE couple_locations SET "UpdatedAtUtc" = {q(at)}, "LastSeenAtUtc" = {q(at)}
                    WHERE "CoupleId" = {qu(c.id)} AND "UserId" = {qu(who.id)} AND "UpdatedAtUtc" >= {q(self.started)}""")

    def e_purchase(self, c, at, buyer, product, state, expires):
        now = dt.datetime.now(UTC)
        days = math.floor((expires - now) / DAY) if expires < now else max(1, math.ceil((expires - now) / DAY))
        token = f"fake.{state}.{days}.{buyer.id.replace('-', '')}.{product}"
        status, _ = self.api("POST", "/api/billing/google/verify", {"productId": product, "purchaseToken": token}, buyer, "purchase")
        if ok(status):
            # Last heard from Google: at the last renewal or expiry for one that ended, within the day for the rest.
            verified = expires if expires < self.plan.horizon else self.plan.horizon - dt.timedelta(hours=random.uniform(1, 20))
            verified = min(self.plan.horizon, max(at, verified))
            sql(f"""UPDATE store_subscriptions SET "CreatedAtUtc" = {q(at)}, "UpdatedAtUtc" = {q(verified)}, "LastVerifiedAtUtc" = {q(verified)},
                      "ExpiresAtUtc" = {q(expires)} WHERE "PurchaseToken" = {q(token)}""")
            STATS.add(f"plus.{state}")

    def e_pregnancy(self, c, at, who, due):
        status, _ = self.api("POST", "/api/pregnancy", {"dueDate": due.isoformat(), "shareWithPartner": True}, who, "pregnancy")
        if ok(status):
            sql(f"""UPDATE pregnancies SET "CreatedAtUtc" = {q(at)}, "UpdatedAtUtc" = {q(at)} WHERE "UserId" = {qu(who.id)} AND "CreatedAtUtc" >= {q(self.started)}""")
            self.notes(c.id, at)

    def e_breakup(self, c, at, by):
        for goal in c.goals.values():
            if goal["active"]:
                goal.update(active=False, updated=at)
        status, _ = self.api("POST", "/api/couple/breakup", {}, by, "breakup")
        if ok(status):
            self.notes(c.id, at)
            sql(f"""UPDATE exercise_goals SET "UpdatedAtUtc" = {q(at)} WHERE "CoupleId" = {qu(c.id)} AND "UpdatedAtUtc" >= {q(self.started)}""")

    def e_reinvite(self, c, at, who):
        if who.deleted:
            return
        status, invite = self.api("POST", "/api/couple/invites", {}, who, "reinvite")
        if ok(status):
            self.stamp_invite(invite["id"], at, at)
            couple = sql(f"""SELECT "CoupleId" FROM partner_invites WHERE "Id" = {qu(invite['id'])}""")[0][0]
            sql(f"""UPDATE couples SET "CreatedAtUtc" = {q(at)}, "UpdatedAtUtc" = {q(at)} WHERE "Id" = {qu(couple)}""")
            who.reinvited = couple

    def delete(self, p):
        status, _ = self.api("POST", "/api/account/delete", {"password": PASSWORD, "confirmEmail": p.email, "acknowledgeActiveSubscription": True}, p, "delete")
        if ok(status):
            p.deleted = True
            STATS.add("people.deleted")

    # Cycle tracking happens for singles and couples alike.

    def cycle(self, p):
        spec = p.cycle
        if not spec or p.deleted or (p.delete_at and spec["at"] > p.delete_at):
            return
        status, _ = call("POST", "/api/cycle/claim", {}, token=p.token, lang=p.accept_language)
        if status == 409:
            STATS.add("skipped.cycle_partner_tracks")  # two women: only one of them can own the couple's cycle
            return
        profile = {"averageCycleLengthDays": spec["length"], "averagePeriodLengthDays": spec["period"],
                   "lastPeriodStartDate": spec["last_start"].isoformat(), "shareLevel": spec["share"]}
        status, _ = self.api("PUT", "/api/cycle/profile", profile, p, "cycle_profile")
        if not ok(status):
            return
        p.cycle_set = spec["at"]
        rng = random.Random(f"cycle:{p.index}")
        stamps = []
        for day, when, flow in spec["logs"]:
            symptoms = rng.sample(SYMPTOMS, rng.choice([0, 1, 1, 2, 3]))
            mood = rng.choice([None, "happy", "sad", "irritable", "anxious", "calm", "sensitive"])
            status, _ = self.api("PUT", f"/api/cycle/logs/{day.isoformat()}", {"flowLevel": flow, "symptoms": symptoms, "mood": mood,
                                                                               "hadSex": None, "protectionUsed": None, "notes": None}, p, "cycle_log")
            if ok(status):
                stamps.append((day, when))
        if stamps:
            sql(f"""UPDATE cycle_day_logs AS x SET "CreatedAtUtc" = v.t, "UpdatedAtUtc" = v.t
                    FROM (VALUES {values([(q(d), q(t)) for d, t in stamps])}) AS v(d, t) WHERE x."UserId" = {qu(p.id)} AND x."LogDate" = v.d""")


class Moves:
    """Picks plausible moves for the games that can be played to the end."""

    def __init__(self, kind, players, rng):
        self.kind, self.rng = kind, rng
        self.left = {p.id: rng.sample(range(5), 5) for p in players}  # skill duel rounds still to play
        self.picks = {}

    def next(self, session, players):
        rng = self.rng
        state = json.loads(session["stateJson"]) if session.get("stateJson") else {}
        turn = players.get(session.get("currentTurnUserId"))
        if self.kind == "skillDuel":
            ready = [pid for pid, rounds in self.left.items() if rounds]
            if not ready:
                return None
            pid = rng.choice(ready)
            index = self.left[pid].pop()
            kind = state["rounds"][index]["kind"]
            person = players[pid]
            score = int(min(100, max(0, rng.gauss(SKILL_MEANS.get(kind, 65) + person.skill, 14))))
            return person, "score", json.dumps({"score": score}), index
        if self.kind == "wouldYouRather":
            waiting = [players[pid] for pid in players if pid not in (state.get("picked") or [])]
            if not waiting:
                return None
            person = rng.choice(waiting)
            round_ = state.get("round", 0)
            if round_ in self.picks and rng.random() < 0.62:
                pick = self.picks[round_]
            else:
                pick = rng.randint(0, 1)
            self.picks.setdefault(round_, pick)
            return person, "pick", None, pick
        if turn is None:
            return None
        if self.kind == "truthOrDare":
            if state.get("phase") == "judging":
                return turn, "judge", "done" if rng.random() < 0.8 else "skipped", None
            return turn, "choose", "truth" if rng.random() < 0.55 else "dare", None
        if self.kind == "coupleQuiz":
            return turn, "answer", rng.choice(QUIZ_WORDS[:4] if rng.random() < 0.5 else QUIZ_WORDS), None
        if self.kind in ("ticTacToe", "connectFour"):
            move = self.grid(state, turn.id)
            return None if move is None else (turn,) + move
        return None

    def grid(self, state, me):
        columns, rows = state["columns"], state["rows"]
        cells = [None] * (columns * rows) if state.get("phase") == "gameOver" else list(state["cells"])
        need = 3 if self.kind == "ticTacToe" else 4
        if self.kind == "ticTacToe":
            options = [(index, index) for index, owner in enumerate(cells) if owner is None]
        else:
            options = []
            for column in range(columns):
                for row in range(rows - 1, -1, -1):
                    if cells[row * columns + column] is None:
                        options.append((column, row * columns + column))
                        break
        if not options:
            return None

        def wins(cell, owner):
            row, column = divmod(cell, columns)
            for dr, dc in ((0, 1), (1, 0), (1, 1), (1, -1)):
                run = 1
                for sign in (1, -1):
                    r, k = row + dr * sign, column + dc * sign
                    while 0 <= r < rows and 0 <= k < columns and (cells[r * columns + k] == owner):
                        run += 1
                        r, k = r + dr * sign, k + dc * sign
                if run >= need:
                    return True
            return False

        others = [pid for pid in state["players"] if pid != me]
        if self.rng.random() < 0.85:
            for index, cell in options:
                if wins(cell, me):
                    return "place" if self.kind == "ticTacToe" else "drop", None, index
            for index, cell in options:
                if others and wins(cell, others[0]):
                    return "place" if self.kind == "ticTacToe" else "drop", None, index
        index, _ = self.rng.choice(options)
        return ("place" if self.kind == "ticTacToe" else "drop"), None, index


# ---------------------------------------------------------------------------------------------------------------------
# Admin, fix-ups, summary


def admin_session(run, plan):
    admin = {"email": ADMIN, "password": PASSWORD, "displayName": "Ops", "dateOfBirth": "1991-03-08", "gender": "preferNotToSay"}
    status, body = call("POST", "/api/auth/register", admin)
    if status not in (200, 409):
        raise RuntimeError(f"Admin registration answered {status}: {body}")
    status, body = call("POST", "/api/admin/auth/login", {"email": ADMIN, "password": PASSWORD})
    secret = body.get("secret") if status == 409 and isinstance(body, dict) else os.environ.get("ADMIN_TOTP_SECRET")
    if secret is None:
        print(f"! Admin sign-in answered {status}: {body}. Already enrolled? Set ADMIN_TOTP_SECRET; admin steps skipped.", file=sys.stderr)
        return None, None
    used = set()

    def code():
        while int(time.time() // 30) in used:
            time.sleep(1)
        used.add(int(time.time() // 30))
        return totp(secret)

    status, session = call("POST", "/api/admin/auth/login", {"email": ADMIN, "password": PASSWORD, "code": code()})
    if not ok(status):
        print(f"! Admin sign-in failed: {status} {session}", file=sys.stderr)
        return secret, None
    token = session["accessToken"]

    def admin_call(method, path, body):
        status, data = call(method, path, body, token=token)
        if status == 403 and isinstance(data, dict) and str(data.get("code", "")).startswith("admin.step_up"):
            status, data = call(method, path, body, token=token, headers={"X-Admin-Code": code()})
        return status, data

    for c in plan.couples:
        if c.grant and c.id:
            status, grant = admin_call("POST", f"/api/admin/couples/{c.id}/grants", {"days": c.grant["days"], "reason": c.grant["reason"]})
            if ok(status):
                given = c.grant["at"]
                sql(f"""UPDATE plus_grants SET "CreatedAtUtc" = {q(given)}, "UpdatedAtUtc" = {q(given)}, "ExpiresAtUtc" = {q(given + c.grant['days'] * DAY)}
                        WHERE "Id" = {qu(grant['id'])};
                        UPDATE admin_audit_events SET "CreatedAtUtc" = {q(given)}, "UpdatedAtUtc" = {q(given)}
                        WHERE "Action" = 'plus.granted' AND "TargetId" = {q(c.id)} AND "CreatedAtUtc" >= {q(run.started)}""")
                STATS.add("plus.granted")
            else:
                STATS.add(f"failed.admin_grant.{status}")
    for p in plan.people:
        if p.disable_at and p.id and not p.deleted:
            status, _ = admin_call("POST", f"/api/admin/users/{p.id}/disable", {"reason": "Spam: sends promo links to strangers' invite codes"})
            if ok(status):
                sql(f"""UPDATE users SET "DisabledAtUtc" = {q(p.disable_at)} WHERE "Id" = {qu(p.id)};
                        UPDATE admin_audit_events SET "CreatedAtUtc" = {q(p.disable_at)}, "UpdatedAtUtc" = {q(p.disable_at)}
                        WHERE "Action" = 'user.disabled' AND "TargetId" = {q(p.id)} AND "CreatedAtUtc" >= {q(run.started)}""")
                p.acts = [a for a in p.acts if a <= p.disable_at]
            else:
                STATS.add(f"failed.admin_disable.{status}")
    return secret, token


def live_now(run, plan):
    """A few people open the app right now: online, and some sharing their location (the share lapses in ten minutes)."""
    rng = random.Random(f"{plan.seed}:live")
    today = [c for c in plan.couples if c.b and c.id and not c.breakup_at and not c.a.internal
             and any(a.date() == plan.today for p in (c.a, c.b) for a in p.acts)]
    rng.shuffle(today)
    online = []
    for c in today[:7]:
        p = rng.choice([c.a, c.b])
        if p.deleted:
            continue
        online.append(p)
        if rng.random() < 0.6:
            status, _ = run.api("POST", "/api/location/start", {}, p, "location_live")
            if ok(status):
                STATS.add("location.live")
        p.act(dt.datetime.now(UTC) - rng.uniform(0, 4) * MIN)
    return online


def fixups(run, plan):
    """Moves what the API stamped "now" (accounts, couples, goals, devices) back to when it happened."""
    people = [p for p in plan.people if p.id and not p.deleted]
    now = dt.datetime.now(UTC)
    for p in people:
        p.acts = [a for a in p.acts if p.signup <= a <= now]
    rows = []
    rng = random.Random(f"{plan.seed}:fixups")
    for p in people:
        acts = sorted(a for a in p.acts if a >= p.signup)
        last = acts[-1] if acts else p.signup
        google = None if p.method == "password" else "1" + "".join(str(rng.randint(0, 9)) for _ in range(20))
        rows.append((qu(p.id), q(p.signup), q(max(p.signup, min(last, p.signup + 2 * DAY))), q(last), q(google),
                     q(p.method == "google"), q(p.method != "password" or rng.random() < 0.3), q(p.internal)))
    for chunk in range(0, len(rows), 300):
        sql(f"""UPDATE users AS u SET "CreatedAtUtc" = v.created, "UpdatedAtUtc" = v.updated, "LastActiveAtUtc" = v.last, "GoogleId" = v.google,
                  "PasswordHash" = CASE WHEN v.google_only THEN NULL ELSE u."PasswordHash" END, "EmailVerified" = v.verified, "IsInternal" = v.internal
                FROM (VALUES {values(rows[chunk:chunk + 300])}) AS v(id, created, updated, last, google, google_only, verified, internal)
                WHERE u."Id" = v.id""")
    # Activity days: one per person per UTC day they did anything (the API also wrote one for today for everyone).
    ids = ", ".join(qu(p.id) for p in people)
    sql(f"""DELETE FROM user_activity_days WHERE "UserId" IN ({ids})""")
    days = sorted({(p.id, a.date()) for p in people for a in p.acts if a >= p.signup and a.date() <= plan.today})
    for chunk in range(0, len(days), 2000):
        sql(f"""INSERT INTO user_activity_days ("UserId", "Day") VALUES {values([(qu(i), q(d)) for i, d in days[chunk:chunk + 2000]])} ON CONFLICT DO NOTHING""")
    # Rows every account gets at sign-up.
    stamps = [(qu(p.id), q(p.signup), q(p.cycle_set or p.signup), q(max(a for a in p.acts) if p.acts else p.signup)) for p in people]
    sql(f"""UPDATE cycle_profiles AS x SET "CreatedAtUtc" = v.signup, "UpdatedAtUtc" = GREATEST(v.signup, v.cycle)
            FROM (VALUES {values(stamps)}) AS v(id, signup, cycle, last) WHERE x."UserId" = v.id;
            UPDATE notification_preferences AS x SET "CreatedAtUtc" = v.signup + interval '1 minute', "UpdatedAtUtc" = v.signup + interval '1 minute'
            FROM (VALUES {values(stamps)}) AS v(id, signup, cycle, last) WHERE x."UserId" = v.id;
            UPDATE device_registrations AS x SET "CreatedAtUtc" = v.signup + interval '40 seconds', "UpdatedAtUtc" = GREATEST(v.last, v.signup + interval '40 seconds'),
              "LastSeenAtUtc" = GREATEST(v.last, v.signup + interval '40 seconds')
            FROM (VALUES {values(stamps)}) AS v(id, signup, cycle, last) WHERE x."UserId" = v.id""")
    # Couples: every account starts one; joining dissolves the joiner's own.
    couples = []
    for c in plan.couples:
        if c.a.couple0:
            linked = c.link_at if c.b and getattr(c, "linked", False) else None
            dissolved = c.breakup_at if linked and c.breakup_at else None
            couples.append((qu(c.a.couple0), q(c.a.signup), q(linked), q(dissolved), q(dissolved or linked or c.a.signup)))
        if c.b and c.b.couple0 and c.b.couple0 != c.id:
            couples.append((qu(c.b.couple0), q(c.b.signup), "NULL", q(c.link_at), q(c.link_at)))
    sql(f"""UPDATE couples AS x SET "CreatedAtUtc" = v.created, "LinkedAtUtc" = v.linked, "DissolvedAtUtc" = CASE WHEN x."Status" = 'Dissolved' THEN v.dissolved END,
              "UpdatedAtUtc" = v.updated
            FROM (VALUES {values(couples)}) AS v(id, created, linked, dissolved, updated) WHERE x."Id" = v.id""")
    # Goals: their own times, and the alarm pointer on the next real ring.
    goals = []
    for c in plan.couples:
        for goal in c.goals.values():
            after = goal["last"] + MIN if goal["last"] else goal["created"]
            due = next_due(goal["hhmm"], goal["tz"], after)
            if goal["active"] and due + (goal["grace"] + 10) * MIN < now:
                due = next_due(goal["hhmm"], goal["tz"], now)  # a window that closed while the script ran: skip to the next
            goals.append((qu(goal["id"]), q(goal["created"]), q(goal["updated"]), q(due)))
    if goals:
        sql(f"""UPDATE exercise_goals AS x SET "CreatedAtUtc" = v.created, "UpdatedAtUtc" = GREATEST(v.created, v.updated), "NextDueAtUtc" = v.due
                FROM (VALUES {values(goals)}) AS v(id, created, updated, due) WHERE x."Id" = v.id""")
    # Photos: Ready rows with fake keys and sizes (no storage behind them).
    photos = []
    for c in plan.couples:
        if not c.id or not c.memories or c.breakup_at or any(p.deleted for p in (c.a, c.b) if p):
            continue
        for m in c.memories:
            mid = uuid.uuid4()
            taken = m["at"] - rng.uniform(0, 240) * MIN if m["source"] != "Camera" else m["at"] - rng.uniform(0.1, 3) * MIN
            ready = m["at"] + rng.uniform(2, 40) * SEC
            ext, ctype = (".mp4", "video/mp4") if m["video"] else (".jpg", "image/jpeg")
            photos.append((qu(mid), qu(c.id), qu(m["by"].id), q(f"couples/{c.id.replace('-', '')}/{mid.hex}{ext}"), q(ctype), q(m["video"]), str(m["size"]),
                           "NULL", q(taken), q(m["source"]), "'Ready'", q(ready), "'image/jpeg'", str(rng.randint(28_000, 90_000)), "NULL",
                           q(taken), qu(uuid.uuid4()), q(m["at"]), q(ready)))
    for chunk in range(0, len(photos), 500):
        sql(f"""INSERT INTO memories ("Id", "CoupleId", "UploadedByUserId", "ObjectKey", "ContentType", "IsVideo", "SizeBytes", "Caption", "TakenAtUtc",
                  "Source", "Status", "ReadyAtUtc", "ThumbnailContentType", "ThumbnailBytes", "UploadWindowEndsAtUtc", "SortAtUtc", "ClientId",
                  "CreatedAtUtc", "UpdatedAtUtc") VALUES {values(photos[chunk:chunk + 500])}""")
    # Notifications the push worker touched after they were moved: keep them in order, and settled like dev skips them.
    sql(f"""UPDATE notifications SET "UpdatedAtUtc" = GREATEST("CreatedAtUtc", COALESCE("ReadAtUtc", "CreatedAtUtc")),
              "PushState" = CASE WHEN "PushState" = 'Pending' THEN 'Skipped' ELSE "PushState" END,
              "PushNextAttemptAtUtc" = NULL, "PushLockedUntilUtc" = NULL
            WHERE "CreatedAtUtc" < {q(run.started)} AND ("UpdatedAtUtc" >= {q(run.started)} OR "PushState" = 'Pending')""")
    admin_signup = at_local("Africa/Lagos", plan.day0, 9)
    sql(f"""UPDATE users SET "CreatedAtUtc" = {q(admin_signup)}, "UpdatedAtUtc" = {q(admin_signup)} WHERE "Email" = {q(ADMIN)};
            UPDATE couples SET "CreatedAtUtc" = {q(admin_signup)}, "UpdatedAtUtc" = {q(admin_signup)} WHERE "UserAId" = (SELECT "Id" FROM users WHERE "Email" = {q(ADMIN)});
            UPDATE cycle_profiles SET "CreatedAtUtc" = {q(admin_signup)}, "UpdatedAtUtc" = {q(admin_signup)} WHERE "UserId" = (SELECT "Id" FROM users WHERE "Email" = {q(ADMIN)})""")


def checks(run):
    """Rows still stamped during the run, and timestamps out of order. All should be zero (or a handful made live)."""
    since = q(run.started)
    found = {}
    for table in ("users", "couples", "partner_invites", "exercise_goals", "exercise_occurrences", "food_entries", "food_scans", "daily_questions",
                  "daily_answers", "game_sessions", "messages", "notifications", "cycle_day_logs", "store_subscriptions", "couple_locations",
                  "pregnancies", "memories", "nutrition_targets", "device_registrations", "plus_grants"):
        found[f"{table} created during the run"] = int(sql(f"""SELECT count(*) FROM {table} WHERE "CreatedAtUtc" >= {since}""")[0][0])
    rules = {
        "dares completed outside their window": """SELECT count(*) FROM exercise_occurrences WHERE "Status" = 'Completed' AND
            ("StartedAtUtc" IS NULL OR "CompletedAtUtc" <= "StartedAtUtc" OR "CompletedAtUtc" > "CompleteByUtc" OR "StartedAtUtc" < "AlarmStartedAtUtc"
             OR "CompleteByUtc" > "DeadlineUtc" + interval '10 minutes' OR "DeadlineUtc" <= "ScheduledAtUtc")""",
        "dares before their goal": """SELECT count(*) FROM exercise_occurrences o JOIN exercise_goals g ON g."Id" = o."GoalId" WHERE o."AlarmStartedAtUtc" < g."CreatedAtUtc" """,
        "dares before the couple linked": """SELECT count(*) FROM exercise_occurrences o JOIN couples c ON c."Id" = o."CoupleId" WHERE o."ScheduledAtUtc" < c."LinkedAtUtc" """,
        "games ending before they start": """SELECT count(*) FROM game_sessions WHERE "EndedAtUtc" < COALESCE("StartedAtUtc", "CreatedAtUtc") OR "StartedAtUtc" < "CreatedAtUtc" """,
        "answers before their question": """SELECT count(*) FROM daily_answers a JOIN daily_questions d ON d."Id" = a."DailyQuestionId" WHERE a."CreatedAtUtc" < d."CreatedAtUtc" """,
        "meals before sign-up": """SELECT count(*) FROM food_entries f JOIN users u ON u."Id" = f."UserId" WHERE f."LoggedAtUtc" < u."CreatedAtUtc" """,
        "couples linked before created": """SELECT count(*) FROM couples WHERE "LinkedAtUtc" < "CreatedAtUtc" OR "DissolvedAtUtc" < COALESCE("LinkedAtUtc", "CreatedAtUtc")""",
        "people active before sign-up": """SELECT count(*) FROM users WHERE "LastActiveAtUtc" < "CreatedAtUtc" """,
    }
    for label, statement in rules.items():
        found[label] = int(sql(statement)[0][0])
    return found


def reset():
    tables = [row[0] for row in sql("""SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename NOT LIKE '\\_\\_%' ESCAPE '\\'""")]
    sql("TRUNCATE " + ", ".join(f'"{t}"' for t in tables) + " RESTART IDENTITY CASCADE")
    print(f"Wiped {len(tables)} tables.")


def main():
    global ARGS, API
    parser = argparse.ArgumentParser(description="Seeds a local Heartpact API with 90 days of believable demo data.")
    parser.add_argument("api", nargs="?", default="http://localhost:5135")
    parser.add_argument("--reset", action="store_true", help="wipe every table's data first (the schema stays)")
    parser.add_argument("--people", type=int, default=240)
    parser.add_argument("--days", type=int, default=90)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--psql", default=os.environ.get("PSQL_DEMO", DEFAULT_PSQL))
    parser.add_argument("--dry-run", action="store_true", help="print the plan's volumes and stop")
    ARGS = parser.parse_args()
    API = ARGS.api.rstrip("/")
    clock = time.time()
    now = dt.datetime.now(UTC)
    plan = Planner(ARGS.seed, ARGS.people, ARGS.days, now).build()
    print(json.dumps(plan.summary(), indent=1, default=str))
    if ARGS.dry_run:
        return

    status, _ = call("GET", "/api/health")
    if status != 200:
        sys.exit(f"The API at {API} isn't answering /api/health ({status}).")
    # The admin console's analytics leave internal accounts out; make sure the column is there (same SQL as the schema upgrade).
    sql("""ALTER TABLE users ADD COLUMN IF NOT EXISTS "IsInternal" boolean NOT NULL DEFAULT false""")
    if ARGS.reset:
        reset()
    elif sql(f"""SELECT 1 FROM users WHERE "Email" = {q(MARKER)}"""):
        sys.exit("Demo data is already here. Run with --reset to wipe and seed again.")

    run = Run(plan)
    run.started = dt.datetime.fromisoformat(sql("SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US')")[0][0]).replace(tzinfo=UTC) - SEC
    with concurrent.futures.ThreadPoolExecutor(ARGS.workers) as pool:
        list(pool.map(run.register, plan.people))
    print(f"Registered {len(plan.people)} people ({time.time() - clock:.0f}s).")

    done = 0
    order = sorted(plan.couples, key=lambda c: -len(c.events))  # the longest stories first, so the pool finishes together
    with concurrent.futures.ThreadPoolExecutor(ARGS.workers) as pool:
        for future in concurrent.futures.as_completed([pool.submit(run.couple, c) for c in order]):
            future.result()
            done += 1
            if done % 25 == 0:
                print(f"  {done}/{len(order)} couples and singles ({time.time() - clock:.0f}s)")
        list(pool.map(run.cycle, plan.people))
    print(f"Lived 90 days ({time.time() - clock:.0f}s).")

    online = live_now(run, plan)
    secret, token = admin_session(run, plan)
    fixups(run, plan)
    print(f"Moved everything to when it happened ({time.time() - clock:.0f}s).")

    problems = checks(run)
    tables = [row[0] for row in sql("""SELECT tablename FROM pg_tables WHERE schemaname = 'public'""")]
    counts = {table: int(sql(f'SELECT count(*) FROM "{table}"')[0][0]) for table in tables}
    print("\nRows per table:")
    for table, count in sorted(counts.items()):
        print(f"  {table:28} {count:7}")
    print("\nConsistency (expect 0):")
    for label, count in problems.items():
        print(f"  {label:44} {count:5}")
    print("\nWhat happened:", json.dumps(dict(sorted(STATS.counts.items())), indent=1))
    if token:
        for name in ("overview", "engagement", "revenue"):
            status, body = call("GET", f"/api/admin/analytics/{name}?days=30", token=token)
            if ok(status) and isinstance(body, dict):
                flat = {k: v for k, v in body.items() if isinstance(v, (int, float, str)) or v is None}
                print(f"\nadmin {name} (30 days):", json.dumps(flat))
    print(f"\nOnline now: {', '.join(p.name for p in online)}")
    print(f"Done in {time.time() - clock:.0f}s.\n")
    print(json.dumps({"adminEmail": ADMIN, "password": PASSWORD, "totpSecret": secret, "accessToken": token,
                      "internal": [p.email for p in plan.people if p.internal], "everyonesPassword": PASSWORD}))


if __name__ == "__main__":
    main()
