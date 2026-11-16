import importlib.util
import json
import os
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("run_eval.py")
SPEC = importlib.util.spec_from_file_location("enterprise_eval_runner", SCRIPT)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class EvalRunnerClassificationTest(unittest.TestCase):
    def test_done_with_answer_is_success(self):
        self.assertEqual("DONE", RUNNER.classify_turn({"done": True, "answer": "流程是……", "events": ["done"]}))

    def test_done_without_text_is_empty_answer(self):
        self.assertEqual("EMPTY_ANSWER", RUNNER.classify_turn({"done": True, "answer": "  ", "events": ["done"]}))

    def test_server_error_is_not_counted_as_success(self):
        self.assertEqual("ERROR", RUNNER.classify_turn({"done": False, "answer": "部分内容", "error": {"code": "MODEL"}}))

    def test_stream_ending_before_done_is_incomplete(self):
        self.assertEqual("INCOMPLETE_NO_DONE", RUNNER.classify_turn({"done": False, "answer": "", "error": {"code": "MISSING_DONE"}}))

    def test_cancel_event_is_recorded_separately(self):
        self.assertEqual("CANCELLED", RUNNER.classify_turn({"done": False, "cancelled": True, "events": ["cancelled"]}))

    def test_permission_case_is_persisted_to_results(self):
        class FakeApi:
            def __init__(self, base):
                self.base = base

            def login(self, actor):
                self.actor = actor

        class MemoryPath:
            content = ""

            def write_text(self, content, encoding=None):
                self.content = content

        case = {"id": "ACL01", "category": "permission", "actor": "hr", "kb": "员工服务"}
        results = {}
        path = MemoryPath()
        with patch.object(RUNNER, "Api", FakeApi), patch.object(
            RUNNER, "run_permission", return_value={"status": "PASS"}
        ):
            result = RUNNER.run_chat_case("http://test", case, {}, 0, path, results)

        self.assertEqual("PASS", result["status"])
        self.assertIn("ACL01", results)
        self.assertEqual("PASS", json.loads(path.content)[0]["status"])

    def test_acl01_records_the_unassigned_library_id_used_for_the_check(self):
        class FakeApi:
            def json(self, method, path):
                self.asserted = (method, path)
                return [{"id": 1, "name": "员工服务"}]

        with patch.dict(os.environ, {"ENTERPRISE_UNASSIGNED_KB_ID": "2"}):
            result = RUNNER.run_permission(FakeApi(), {"id": "ACL01"}, {})

        self.assertEqual("PASS", result["status"])
        self.assertEqual("2", result["targetKnowledgeBaseId"])
        self.assertEqual([1], result["observedLibraryIds"])


if __name__ == "__main__":
    unittest.main()
