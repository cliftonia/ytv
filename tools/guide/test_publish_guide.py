#!/usr/bin/env python3
"""The server's guide job: publish_guide.sh against a throwaway origin, and the units that run it.

The builder itself is tested in curation/tests/test_build_fast_guide.py; here it is a stub, so
what is checked is the job around it - only fast_guide.json is ever committed, a refusal publishes
nothing, an unchanged guide pushes nothing, and the details job follows the guide's.
"""
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SCRIPT = os.path.join(HERE, "publish_guide.sh")

# Writes what it was asked to - $GUIDE_BODY, and the relay prefix it was given - or refuses.
STUB_BUILDER = '''import json, os, sys
if os.environ.get("GUIDE_REFUSE"):
    print("REFUSING TO PUBLISH: only 1 of 400 guide channels have programmes", file=sys.stderr)
    sys.exit(1)
out = sys.argv[sys.argv.index("--out") + 1]
with open(out, "w") as f:
    json.dump({"body": os.environ.get("GUIDE_BODY", "one"), "via": os.environ.get("YTV_TUBI_VIA")}, f)
'''
STUB_TEST = '''import unittest
class T(unittest.TestCase):
    def test_ok(self):
        self.assertTrue(True)
'''


def git(cwd, *args):
    return subprocess.run(["git", "-c", "core.hooksPath=/dev/null", *args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout


class PublishGuideTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        t = self.tmp.name
        self.origin, self.seed, self.clone = (os.path.join(t, n) for n in ("origin.git", "seed", "clone"))
        git(t, "init", "-q", "--bare", "-b", "main", self.origin)
        os.makedirs(os.path.join(self.seed, "curation", "tests"))
        for path, text in (("curation/build_fast_guide.py", STUB_BUILDER),
                           ("curation/tests/test_build_fast_guide.py", STUB_TEST),
                           ("live.json", '{"channels": []}\n'), ("fast_guide.json", "{}\n")):
            with open(os.path.join(self.seed, path), "w") as f:
                f.write(text)
        git(t, "init", "-q", "-b", "main", self.seed)
        self.identity(self.seed)
        git(self.seed, "add", "-A")
        git(self.seed, "commit", "-q", "-m", "seed")
        git(self.seed, "push", "-q", self.origin, "main")
        git(t, "clone", "-q", self.origin, self.clone)
        self.identity(self.clone)
        # No flock on a Mac: the lock is the server's business, not this test's.
        shims = os.path.join(t, "bin")
        os.makedirs(shims)
        with open(os.path.join(shims, "flock"), "w") as f:
            f.write("#!/bin/sh\nexit 0\n")
        os.chmod(os.path.join(shims, "flock"), 0o755)
        self.env = dict(os.environ, PATH=shims + os.pathsep + os.environ["PATH"],
                        YTV_GUIDE_REPO=self.clone, YTV_GUIDE_STATE=os.path.join(t, "state"),
                        YTV_TUBI_VIA="http://127.0.0.1:4247/hls?u=")

    def tearDown(self):
        self.tmp.cleanup()

    @staticmethod
    def identity(repo):
        git(repo, "config", "user.name", "ytv-server")
        git(repo, "config", "user.email", "ytv-server@users.noreply.github.com")

    def run_job(self, **env):
        return subprocess.run(["bash", SCRIPT], env=dict(self.env, **env), capture_output=True,
                              text=True, timeout=120)

    def published(self):
        return git(self.origin, "show", "main:fast_guide.json")

    def test_a_guide_is_published_alone_with_tubi_through_the_relay(self):
        with open(os.path.join(self.clone, "stray.txt"), "w") as f:
            f.write("not ours")
        job = self.run_job()
        self.assertEqual(0, job.returncode, job.stderr)
        self.assertIn('"via": "http://127.0.0.1:4247/hls?u="', self.published())
        self.assertEqual("fast_guide.json\n", git(self.origin, "show", "--name-only", "--format=", "main"))
        self.assertIn("chore: refresh the FAST guide", git(self.origin, "log", "-1", "--format=%s", "main"))

    def test_an_unchanged_guide_pushes_nothing(self):
        self.assertEqual(0, self.run_job().returncode)
        head = git(self.origin, "rev-parse", "main")
        job = self.run_job()
        self.assertEqual(0, job.returncode, job.stderr)
        self.assertIn("nothing to publish", job.stdout)
        self.assertEqual(head, git(self.origin, "rev-parse", "main"))

    def test_a_refusal_publishes_nothing_and_fails_so_details_do_not_follow(self):
        head = git(self.origin, "rev-parse", "main")
        job = self.run_job(GUIDE_REFUSE="1")
        self.assertNotEqual(0, job.returncode)
        self.assertEqual(head, git(self.origin, "rev-parse", "main"))
        self.assertEqual("{}\n", self.published())

    def test_it_rebases_over_what_landed_meanwhile(self):
        with open(os.path.join(self.seed, "live.json"), "w") as f:
            f.write('{"channels": [1]}\n')
        git(self.seed, "commit", "-q", "-am", "a new lineup")
        git(self.seed, "push", "-q", self.origin, "main")
        job = self.run_job(GUIDE_BODY="two")
        self.assertEqual(0, job.returncode, job.stderr)
        self.assertIn('"body": "two"', self.published())
        self.assertIn("a new lineup", git(self.origin, "log", "--format=%s", "main"))

    def test_push_off_builds_and_commits_nothing(self):
        head = git(self.origin, "rev-parse", "main")
        job = self.run_job(YTV_GUIDE_PUSH="0")
        self.assertEqual(0, job.returncode, job.stderr)
        self.assertEqual(head, git(self.origin, "rev-parse", "main"))
        self.assertTrue(os.path.exists(os.path.join(self.env["YTV_GUIDE_STATE"], "fast_guide.json")))


class UnitsTest(unittest.TestCase):
    """Who publishes the guide, and when the details follow."""

    def read(self, *path):
        with open(os.path.join(ROOT, *path)) as f:
            return f.read()

    def test_every_three_hours_at_five_past_utc(self):
        self.assertIn("OnCalendar=*-*-* 00/3:05:00 UTC", self.read("tools/guide/systemd/ytv-guide.timer"))

    def test_the_details_follow_each_successful_guide_run_and_have_no_timer(self):
        service = self.read("tools/guide/systemd/ytv-guide.service")
        self.assertIn("OnSuccess=ytv-details.service", service)
        self.assertIn("Environment=YTV_TUBI_VIA=http://127.0.0.1:4247/hls?u=", service)
        self.assertFalse(os.path.exists(os.path.join(ROOT, "tools/details/systemd/ytv-details.timer")))

    def test_the_workflow_is_manual_only(self):
        workflow = self.read(".github/workflows/fast_guide.yml")
        self.assertIn("workflow_dispatch", workflow)
        self.assertNotIn("schedule:", workflow)
        self.assertNotIn("cron:", workflow)


if __name__ == "__main__":
    unittest.main()
