#!/usr/bin/env python3
"""
Seeds a local API with a few couples, then signs in as the admin (enrolling the authenticator) and
prints the admin token and authenticator secret. Development only: it needs the API started with
Admin__Emails=ops@heartpact.me, Billing__Provider=fake, Email__Provider=log and RateLimits__Enabled=false.

    python3 admin/scripts/seed.py http://localhost:5135

If the admin enrolled on an earlier run, pass the authenticator secret it printed: ADMIN_TOTP_SECRET=...
"""
import base64, hashlib, hmac, json, os, struct, sys, time, urllib.request, urllib.error, uuid

API = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5135").rstrip("/")
ADMIN = "ops@heartpact.me"
PASSWORD = "longenough"

def call(method, path, body=None, token=None):
    req = urllib.request.Request(API + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"content-type": "application/json", **({"authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req) as res:
            text = res.read().decode()
            return res.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, (json.loads(text) if text else None)

def totp(secret, at=None):
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    step = int((at or time.time()) // 30)
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(struct.unpack('>I', digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"

def person(email, name, gender):
    status, body = call("POST", "/api/auth/register", {"email": email, "password": PASSWORD, "displayName": name, "dateOfBirth": "1995-04-02", "gender": gender})
    if status == 409:
        status, body = call("POST", "/api/auth/login", {"email": email, "password": PASSWORD})
    assert status == 200, (email, status, body)
    return body

def couple(a, b):
    invite = call("POST", "/api/couple/invites", {}, a["accessToken"])[1]
    status, accepted = call("POST", "/api/couple/invites/accept", {"code": invite["code"]}, b["accessToken"])
    if status == 200:
        b["accessToken"] = accepted["accessToken"]
        a.update(call("POST", "/api/auth/login", {"email": a["user"]["email"], "password": PASSWORD})[1])

run = uuid.uuid4().hex[:6]
people = []
for i, (n1, n2) in enumerate([("Maya", "Theo"), ("Ada", "Femi"), ("Zoe", "Liam"), ("Ife", "Tobi")]):
    a = person(f"{n1.lower()}.{run}@example.com", n1, "female")
    b = person(f"{n2.lower()}.{run}@example.com", n2, "male")
    if i < 3: couple(a, b)
    people += [a, b]
    # A day of use: Today and a meal, so the charts have something.
    call("GET", "/api/exercise/today", token=a["accessToken"])
    call("POST", "/api/food/entries", {"localDate": time.strftime("%Y-%m-%d", time.gmtime()), "mealType": "lunch", "description": "Jollof rice", "calories": 650}, a["accessToken"])
# One paying couple (fake Play purchase owned by the buyer's account id).
buyer = people[0]
account = buyer["user"]["id"].replace("-", "")
print("purchase", call("POST", "/api/billing/google/verify", {"productId": "heartpact_plus_monthly", "purchaseToken": f"fake.active.30.{account}.heartpact_plus_monthly"}, buyer["accessToken"])[0])

person(ADMIN, "Ops", "preferNotToSay")
status, body = call("POST", "/api/admin/auth/login", {"email": ADMIN, "password": PASSWORD})
secret = body.get("secret") if status == 409 else os.environ.get("ADMIN_TOTP_SECRET")
if secret is None:
    sys.exit(f"Admin sign-in answered {status}: {body}. Already enrolled? Set ADMIN_TOTP_SECRET.")
status, session = call("POST", "/api/admin/auth/login", {"email": ADMIN, "password": PASSWORD, "code": totp(secret)})
assert status == 200, (status, session)
print(json.dumps({"adminEmail": ADMIN, "password": PASSWORD, "totpSecret": secret, "accessToken": session["accessToken"], "users": [p["user"]["email"] for p in people]}))
