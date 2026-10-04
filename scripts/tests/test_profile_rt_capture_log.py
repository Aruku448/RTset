import importlib.util
from pathlib import Path
import unittest

module_path = Path(__file__).resolve().parents[1] / "profile_rt_capture_log.py"
spec = importlib.util.spec_from_file_location("profile_rt_capture_log", module_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CaptureProfileTest(unittest.TestCase):
    def test_full_stale_capture_has_elapsed_time_not_worker_compute_time(self):
        result = module.profile("\n".join([
            "[21:33:10] [Render thread/INFO]: RTest queued incremental capture of 4273 sections within 9 chunks",
            "[21:34:04] [Render thread/INFO]: RTest queued full scene finalization for the geometry worker",
            "[21:34:05] [Render thread/INFO]: RTest discarded stale full scene finalization (captureGeneration=1, sceneGeneration=2)",
        ]))
        self.assertEqual(result["counts"], {"total": 1, "stale": 1})
        self.assertEqual(result["cycles"][0]["duration_s_coarse"], 55)
        self.assertEqual(result["cycles"][0]["kind"], "full")

    def test_dirty_capture_is_not_full_capture(self):
        result = module.profile("\n".join([
            "[22:22:13] [Render thread/INFO]: RTest queued incremental capture of 16 sections within 4 chunks",
            "[22:22:13] [Render thread/INFO]: RTest queued 512 dirty-section updates (16 sections to capture)",
            "[22:22:14] [Render thread/INFO]: RTest applied dirty-section update; scene now has 10 triangles",
        ]))
        self.assertEqual(result["cycles"][0]["kind"], "dirty")
        self.assertEqual(result["cycles"][0]["sections"], 16)
        self.assertEqual(result["cycles"][0]["duration_s_coarse"], 1)
        self.assertEqual(result["counts"]["stale"], 0)

    def test_ignores_unmatched_events_and_unfinished_capture(self):
        result = module.profile("\n".join([
            "[22:22:13] [Render thread/INFO]: RTest applied full scene snapshot",
            "[22:22:14] [Render thread/INFO]: RTest queued incremental capture of 90 sections within 4 chunks",
        ]))
        self.assertEqual(result["cycles"], [])

    def test_window_does_not_pair_completion_with_start_outside_window(self):
        text = "\n".join([
            "[22:22:13] [Render thread/INFO]: RTest queued incremental capture of 90 sections within 4 chunks",
            "[22:22:14] [Render thread/INFO]: RTest applied full scene snapshot",
        ])
        self.assertEqual(module.profile(text, since="22:22:14")["cycles"], [])


if __name__ == "__main__":
    unittest.main()
