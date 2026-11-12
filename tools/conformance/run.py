#!/usr/bin/env python3
"""Floor42's OIDC sign-in against the OpenID Foundation conformance suite (RP tests). See docs/oidc-conformance.md.

Starts the packaged daemon (IAM mode, issuer = the suite's issuer, a trust store holding the suite's self-signed
certificate), creates the plan, runs every module of it and drives the sign-in each one needs, then writes
target/oidc-conformance/results.json and results.md. Python 3 standard library only. Linux or WSL.
"""
import http.client
import json
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "target", "oidc-conformance")
HOST = "localhost.emobix.co.uk"
SUITE = f"https://{HOST}:8443"
ALIAS = "floor42"
ISSUER = f"{SUITE}/test/a/{ALIAS}/"
PORT = int(os.environ.get("FLOOR42_CONFORMANCE_PORT", "7431"))
CLIENT = "floor42-desk"
VARIANT = {
    "client_registration": "static_client",
    "response_type": "code",
    "response_mode": "default",
    "client_auth_type": "none",
    "request_type": "plain_http_request",
}
_CONTEXT = None


def suite_context():
    """TLS verified against the certificate the local suite serves (taken once from the pinned 127.0.0.1), hostname checked."""
    global _CONTEXT
    if _CONTEXT is None:
        _CONTEXT = ssl.create_default_context(cadata=ssl.get_server_certificate((HOST, 8443)))
    return _CONTEXT


# The suite's host name is pinned to 127.0.0.1 here and, by a hosts file, in the daemon: no public DNS answer is trusted.
_resolve = socket.getaddrinfo
socket.getaddrinfo = lambda host, *a, **k: _resolve("127.0.0.1" if host == HOST else host, *a, **k)

# What Floor42 must do for each module: "accept" (303, a session, no refusal logged), ("reject", rule, keys_fetched): 401
# SIGN_IN_FAILED, the suite saw the token request, Floor42 logged "ID token refused: rule=<rule>" (so the token request answered
# and the claims check refused it), and the suite saw the keys fetched when keys_fetched; ("known", reason): a deviation
# stricter than the specification, Floor42 refuses; ("na", reason): the module does not apply. A module missing here fails the run.
NA_DISCOVERY = "Floor42 does not use discovery: the endpoint paths are derived from the configured issuer (Keycloak's)"
NA_USERINFO = "Floor42 never calls the userinfo endpoint: the director's identity comes from the signed ID token only"
EXPECT = {
    "oidcc-client-test": "accept",
    "oidcc-client-test-nonce-invalid": ("reject", "nonce", True),
    "oidcc-client-test-client-secret-basic": ("na", "Floor42 is a public client (client_auth_type none): it holds no secret"),
    "oidcc-client-test-scope-userinfo-claims": ("na", NA_USERINFO),
    "oidcc-client-test-kid-absent-single-jwks": ("known", "Keycloak always sets kid; Floor42 refuses an ID token without one (stricter than the suite's optional behaviour)"),
    "oidcc-client-test-kid-absent-multiple-jwks": ("reject", "kid", False),
    "oidcc-client-test-missing-iat": ("reject", "iat", True),
    "oidcc-client-test-missing-aud": ("reject", "aud", True),
    "oidcc-client-test-invalid-aud": ("reject", "aud", True),
    "oidcc-client-test-idtoken-sig-none": ("reject", "alg", False),
    "oidcc-client-test-idtoken-sig-rs256": "accept",
    "oidcc-client-test-missing-sub": ("reject", "sub", True),
    "oidcc-client-test-invalid-sig-rs256": ("reject", "signature", True),
    "oidcc-client-test-invalid-iss": ("reject", "iss", True),
    "oidcc-client-test-userinfo-invalid-sub": ("na", NA_USERINFO),
    "oidcc-client-test-userinfo-bearer-header": ("na", NA_USERINFO),
    "oidcc-client-test-userinfo-bearer-body": ("na", NA_USERINFO),
    "oidcc-client-test-invalid-sig-es256": ("reject", "alg", False),
    "oidcc-client-test-aggregated-claims": ("na", NA_USERINFO),
    "oidcc-client-test-distributed-claims": ("na", NA_USERINFO),
    "oidcc-client-test-discovery-openid-config": ("na", NA_DISCOVERY),
    "oidcc-client-test-discovery-jwks-uri-keys": ("na", NA_DISCOVERY),
    "oidcc-client-test-discovery-issuer-mismatch": ("na", NA_DISCOVERY),
    "oidcc-client-test-signing-key-rotation-just-before-signing": "accept",
    "oidcc-client-test-signing-key-rotation": "accept",
    "oidcc-client-test-discovery-webfinger-acct": ("na", NA_DISCOVERY),
    "oidcc-client-test-discovery-webfinger-url": ("na", NA_DISCOVERY),
    "oidcc-client-test-userinfo-signed": ("na", NA_USERINFO),
}


TWICE = {"oidcc-client-test-signing-key-rotation"}


def api(method, path, body=None, query=None):
    url = f"{SUITE}/api/{path}" + ("?" + urllib.parse.urlencode(query) if query else "")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, method=method, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, context=suite_context(), timeout=60) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def wait_suite():
    for _ in range(120):
        try:
            api("GET", "runner/available")
            return
        except Exception:
            time.sleep(2)
    sys.exit("The suite does not answer on " + SUITE + ": run `docker compose -f tools/conformance/compose.yaml up -d`")


class Daemon:
    def __init__(self):
        self.base = tempfile.mkdtemp(prefix="floor42-oidc-")
        self.proc = None

    def start(self):
        jar = os.path.join(ROOT, "floor42-server", "target", "quarkus-app", "quarkus-run.jar")
        if not os.path.exists(jar):
            sys.exit("No " + jar + ": run ./mvnw -B -ntp -q package -DskipTests first")
        cert = ssl.get_server_certificate((HOST, 8443))
        pem = os.path.join(self.base, "suite.pem")
        open(pem, "w").write(cert)
        hosts = os.path.join(self.base, "hosts")
        open(hosts, "w").write("127.0.0.1 " + HOST + chr(10))
        trust = os.path.join(self.base, "trust.p12")
        subprocess.run(["keytool", "-importcert", "-noprompt", "-alias", "suite", "-file", pem, "-keystore", trust,
                        "-storetype", "PKCS12", "-storepass", "changeit"], check=True, capture_output=True)
        java = os.environ.get("JAVA") or (os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java")
        fake = os.path.join(ROOT, "floor42-server", "src", "test", "resources", "fake-claude")
        tty = os.path.join(ROOT, "floor42-server", "src", "test", "resources", "fake-tty")
        b = self.base
        cmd = [java, f"-Dquarkus.http.port={PORT}", f"-Dfloor42.data-dir={b}/data",
               f"-Dfloor42.legacy-data-dir={b}/no-legacy", "-Dfloor42.sign-in.delivery=stdout",
               f"-Dfloor42.claude.command={java},{fake}/FakeClaude.java,--fake-script,{fake}/session.txt,--fake-review-script,{fake}/review.txt",
               "-Dfloor42.claude.hook-timeout-seconds=60", f"-Dfloor42.catalogue.claude-home={b}/claude-home",
               "-Dfloor42.gh.command=floor42-no-gh", f"-Dfloor42.backup.dir={b}/backups",
               "-Dfloor42.plugins.claude-command=floor42-no-claude", "-Dfloor42.plugins.listing-base=http://127.0.0.1:9",
               f"-Dfloor42.terminal.command={java},{tty}/FakeTty.java,--claude-prompt",
               f"-Dfloor42.terminal.claude-home={b}/claude-home", "-Dfloor42.retro.enabled=false",
               "-Dfloor42.sandbox.distro=floor42-no-such-distro", "-Dfloor42.sandbox.mandatory=false",
               f"-Djdk.net.hosts.file={hosts}", "-Dfloor42.iam.enabled=true", f"-Dfloor42.iam.issuer={ISSUER}",
               f"-Djavax.net.ssl.trustStore={trust}", "-Djavax.net.ssl.trustStorePassword=changeit",
               "-jar", jar]
        self.log = open(os.path.join(b, "daemon.log"), "w")
        self.proc = subprocess.Popen(cmd, stdout=self.log, stderr=subprocess.STDOUT)
        for _ in range(180):
            try:
                c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=3)
                c.request("GET", "/auth/keycloak/enabled")
                if c.getresponse().status == 204:
                    return
            except OSError:
                pass
            if self.proc.poll() is not None:
                sys.exit("The daemon exited:\n" + open(os.path.join(b, "daemon.log")).read()[-3000:])
            time.sleep(1)
        sys.exit("The daemon did not start")

    def log_size(self):
        self.log.flush()
        return os.path.getsize(os.path.join(self.base, "daemon.log"))

    def refusals_since(self, offset):
        """The rules named by the ID token refusals the daemon logged since offset."""
        time.sleep(0.5)
        with open(os.path.join(self.base, "daemon.log"), "rb") as f:
            f.seek(offset)
            return re.findall(r"ID token refused: rule=([a-z]+)", f.read().decode("utf-8", "replace"))

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        shutil.rmtree(self.base, ignore_errors=True)


def sign_in():
    """One browser sign-in: Floor42 start, the suite's authorize, Floor42 callback. Returns (status, body, session)."""
    jar = {}

    def hop(conn, method, target):
        hdr = {"Cookie": "; ".join(f"{k}={v}" for k, v in jar.items())} if conn.host == "127.0.0.1" else {}
        conn.request(method, target, headers=hdr)
        r = conn.getresponse()
        body = r.read().decode("utf-8", "replace")
        if conn.host == "127.0.0.1":
            for k, v in r.getheaders():
                if k.lower() == "set-cookie":
                    name, _, val = v.split(";")[0].partition("=")
                    jar[name] = val
        return r, body

    target, host = "/auth/keycloak", None
    for _ in range(6):
        if host is None or host == "127.0.0.1":
            conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=30)
        else:
            conn = http.client.HTTPSConnection(host, 8443, timeout=60, context=suite_context())
        r, body = hop(conn, "GET", target)
        loc = r.getheader("Location")
        if r.status in (301, 302, 303) and loc:
            u = urllib.parse.urlparse(loc)
            if r.status == 303 and conn.host == "127.0.0.1" and u.path == "/":
                return r.status, "", True
            if (u.hostname or "127.0.0.1") not in ("127.0.0.1", HOST):
                return 0, "redirect to a host that is not the suite: " + str(u.hostname), False
            host, target = (u.hostname or "127.0.0.1"), u.path + ("?" + u.query if u.query else "")
            continue
        return r.status, body, False
    return 0, "too many redirects", False


def start_module(plan_id, name):
    return api("POST", "runner", None, {"test": name, "plan": plan_id, "variant": json.dumps(VARIANT)})["id"]


def info(mid):
    return api("GET", f"info/{mid}")


def log(mid):
    return api("GET", f"log/{mid}")


def main():
    only = sys.argv[1:]
    os.makedirs(OUT, exist_ok=True)
    wait_suite()
    d = Daemon()
    d.start()
    try:
        cfg = {"alias": ALIAS, "description": "Floor42 RP", "publish": "",
               "client": {"client_id": CLIENT, "redirect_uri": f"http://127.0.0.1:{PORT}/auth/callback"}}
        plan = api("POST", "plan", cfg, {"planName": "oidcc-client-test-plan", "variant": json.dumps(VARIANT)})
        results = []
        for m in plan["modules"]:
            name = m["testModule"]
            if only and name not in only:
                continue
            exp = EXPECT.get(name)
            if exp is None:
                results.append({"module": name, "verdict": "FAIL", "note": "not in the expectation table of run.py: classify it"})
                print("FAIL  " + name + " is not in the expectation table", flush=True)
                continue
            if isinstance(exp, tuple) and exp[0] in ("na", "known"):
                results.append({"module": name, "verdict": "NOT APPLICABLE" if exp[0] == "na" else "KNOWN DEVIATION", "note": exp[1]})
                continue
            offset = d.log_size()
            mid = start_module(plan["id"], name)
            for _ in range(60):
                if info(mid)["status"] in ("WAITING", "FINISHED", "INTERRUPTED"):
                    break
                time.sleep(0.5)
            status, body, session = sign_in()
            if name in TWICE and status == 303:
                status, body, session = sign_in()
            time.sleep(1)
            got = "accept" if status == 303 and session else "reject" if status == 401 else f"other({status})"
            refusals = d.refusals_since(offset)
            try:
                code = json.loads(body).get("code") if body else None
            except ValueError:
                code = None
            inf = info(mid)
            entries = log(mid)
            bad = [f"{e.get('result')}: {e.get('src')}: {e.get('msg')}" for e in entries if e.get("result") in ("FAILURE", "WARNING")]
            if os.environ.get("DEBUG"):
                for e in entries:
                    print("   LOG", e.get("result"), e.get("src"), str(e.get("msg"))[:200], flush=True)
                with urllib.request.urlopen(ISSUER + "jwks", context=suite_context()) as jr:
                    for k in json.load(jr)["keys"]:
                        print("   KEY", {a: (str(v)[:24]) for a, v in k.items() if a != "d"}, flush=True)
            if inf.get("status") not in ("FINISHED", "INTERRUPTED"):
                try:
                    api("DELETE", f"runner/{mid}")
                except Exception:
                    pass
            msgs = [str(e.get("msg")) for e in entries]
            reached_token = f"Incoming HTTP request to /test/a/{ALIAS}/token" in msgs
            fetched_keys = f"Incoming HTTP request to /test/a/{ALIAS}/jwks" in msgs
            problems = [b for b in bad if b.startswith("FAILURE")]
            if exp == "accept":
                if got != "accept" or refusals:
                    problems.append(f"expected an accept, got {got} and refusals {refusals}")
            else:
                _, rule, keys = exp
                if got != "reject" or code != "SIGN_IN_FAILED":
                    problems.append(f"expected 401 SIGN_IN_FAILED, got {status} {code}")
                if not reached_token:
                    problems.append("the suite saw no token request: the refusal came before it")
                if refusals != [rule]:
                    problems.append(f"expected the refusal rule {rule}, Floor42 logged {refusals}")
                if keys and not fetched_keys:
                    problems.append("the suite saw no JWKS fetch")
            ok = not problems
            for p in problems:
                print("      " + p, flush=True)
            results.append({"module": name, "id": mid, "expected": exp if isinstance(exp, str) else f"reject ({exp[1]})", "floor42": got, "http": status,
                            "suite_status": inf.get("status"), "suite_result": inf.get("result"), "suite_findings": bad, "token_endpoint_reached": reached_token, "keys_fetched": fetched_keys, "refusal_rules": refusals, "problems": problems,
                            "verdict": "PASS" if ok else "FAIL", "body": body[:200]})
            print(f"{'PASS' if ok else 'FAIL'}  {name}  expected {exp}, Floor42 {got} (HTTP {status}) rules {refusals}; suite {inf.get('status')}/{inf.get('result')}", flush=True)
            for b in bad:
                print("      " + b[:240], flush=True)
        json.dump({"plan": plan["id"], "variant": VARIANT, "results": results}, open(os.path.join(OUT, "results.json"), "w"), indent=1)
        with open(os.path.join(OUT, "results.md"), "w") as md:
            md.write("| Module | Expected | Floor42 | Verdict | Note |\n|---|---|---|---|---|\n")
            for r in results:
                note = r.get("note") or ("refusal rule: " + ", ".join(r.get("refusal_rules", [])) if r.get("refusal_rules") else "") or "; ".join(r.get("suite_findings", []))
                md.write(f"| {r['module']} | {r.get('expected', '')} | {r.get('floor42', '')} | {r['verdict']} | {note} |\n")
        failed = [r["module"] for r in results if r["verdict"] == "FAIL"]
        print(f"{sum(r['verdict'] == 'PASS' for r in results)} passed, {len(failed)} failed, "
              f"{sum(r['verdict'] == 'KNOWN DEVIATION' for r in results)} known deviation, "
              f"{sum(r['verdict'] == 'NOT APPLICABLE' for r in results)} not applicable. Results: target/oidc-conformance/results.md")
        if failed:
            sys.exit(1)
    finally:
        d.stop()


if __name__ == "__main__":
    main()
