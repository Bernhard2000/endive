"""Negative controls for abstats.py: python3 -m unittest test_abstats (in this directory)."""
import json, math, tempfile, unittest
from pathlib import Path

from abstats import CellError, compare_outputs, load_runs, normalized

RECORD = '{"label":"%s","exec_ms_median":1.0,"compile_ms":2.0}'


class LoadRuns(unittest.TestCase):
    def runs(self, up, fork, orders=None, drop=None):
        d = {"runs_ns": {"up": up, "fork": fork}}
        if drop:
            del d["runs_ns"][drop]
        if orders is not None:
            d["round_orders"] = orders
        p = Path(self.dir.name) / "results.steady.json"
        p.write_text(json.dumps(d, allow_nan=True))
        return load_runs(p)

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.dir.cleanup()

    def test_valid(self):
        self.assertEqual(self.runs([100, 110], [90, 99], [["up", "fork"]] * 2),
                         ([100, 110], [90, 99]))

    def test_rejects(self):
        for up, fork in (([100, 100, 100], [90]), ([], []), ([100, 0], [90, 90]),
                         ([100, -1], [90, 90]), ([100, math.nan], [90, 90]),
                         ([100, math.inf], [90, 90]), ([100, "9"], [90, 90]),
                         ([100, True], [90, 90])):
            with self.subTest(up=up, fork=fork), self.assertRaises(CellError):
                self.runs(up, fork)

    def test_rejects_missing_arm_and_round_orders(self):
        with self.assertRaises(CellError):
            self.runs([100], [90], drop="fork")
        with self.assertRaises(CellError):
            self.runs([100, 100], [90, 90], orders=[["up", "fork"]])


class Outputs(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.out = Path(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def write(self, arm, rnd, guest):
        (self.out / f"{arm}.r{rnd}.out").write_text(guest + "\n" + RECORD % arm + "\n")

    def test_masks_only_volatile_timings_and_the_harness_record(self):
        self.assertEqual(normalized("Bounce: iterations=1 runtime: 219487us\n" + RECORD % "up"),
                         "Bounce: iterations=1 runtime: <t>us")
        self.assertNotEqual(normalized('{"answer":1}'), normalized('{"answer":2}'))
        self.assertNotEqual(normalized("took 3 s, result 12 ms"),
                            normalized("took 3 s, result 13 ms"))

    def test_identical(self):
        for r in (1, 2):
            self.write("up", r, "Total Runtime: 1us\nok")
            self.write("fork", r, "Total Runtime: 2us\nok")
        self.assertEqual(compare_outputs(self.out, 2), "yes (4 of 4)")

    def test_different_guest_json_fails(self):
        self.write("up", 1, '{"answer":1}')
        self.write("fork", 1, '{"answer":2}')
        with self.assertRaises(CellError):
            compare_outputs(self.out, 1)

    def test_missing_arm_fails(self):
        self.write("up", 1, "ok")
        with self.assertRaises(CellError):
            compare_outputs(self.out, 1, partial=True)

    def test_missing_round_fails_unless_partial(self):
        self.write("up", 1, "ok")
        self.write("fork", 1, "ok")
        with self.assertRaises(CellError):
            compare_outputs(self.out, 12)
        self.assertEqual(compare_outputs(self.out, 12, partial=True), "yes (2 of 24)")

    def test_empty_output_against_expected_output_fails(self):
        self.write("up", 1, "result 42")
        self.write("fork", 1, "")
        with self.assertRaises(CellError):
            compare_outputs(self.out, 1)


if __name__ == "__main__":
    unittest.main()
