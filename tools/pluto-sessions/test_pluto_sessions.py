#!/usr/bin/env python3
"""The session cache's rules: one session per television per region, refreshed before it lapses.

Two televisions on one Pluto session knock each other off (one stream per session), so the rule
that matters most is that a session is never handed to a second caller.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pluto_sessions


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def cache_with(clock):
    minted = []

    def fetch(region):
        minted.append(region)
        return {"jwt": "t%d" % len(minted), "region": region, "expiresAt": clock() + 3 * 3600}

    return pluto_sessions.SessionCache(fetch, now=clock), minted


class TestSessionCache(unittest.TestCase):

    def test_a_television_keeps_its_session(self):
        clock = Clock()
        cache, minted = cache_with(clock)
        first = cache.get("100.1.1.1", "uk")
        clock.t += 600
        self.assertIs(first, cache.get("100.1.1.1", "uk"))
        self.assertEqual(["uk"], minted)

    def test_two_televisions_never_share_a_session(self):
        cache, _ = cache_with(Clock())
        self.assertNotEqual(cache.get("100.1.1.1", "uk")["jwt"], cache.get("100.1.1.2", "uk")["jwt"])

    def test_each_region_is_its_own_session(self):
        cache, minted = cache_with(Clock())
        cache.get("100.1.1.1", "uk")
        cache.get("100.1.1.1", "us")
        self.assertEqual(["uk", "us"], minted)

    def test_a_session_is_replaced_before_it_lapses(self):
        clock = Clock()
        cache, _ = cache_with(clock)
        first = cache.get("100.1.1.1", "us")
        clock.t += 3 * 3600 - 1700  # inside the half-hour refresh margin
        self.assertNotEqual(first["jwt"], cache.get("100.1.1.1", "us")["jwt"])


if __name__ == "__main__":
    unittest.main()
