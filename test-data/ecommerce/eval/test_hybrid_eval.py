import importlib.util
import json
import ssl
import tempfile
import unittest
import urllib.error
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_hybrid_eval.py")
SPEC = importlib.util.spec_from_file_location("run_hybrid_eval", MODULE_PATH)
EVAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVAL)


class HybridEvalParserTests(unittest.TestCase):
    def test_sse_joins_data_tokens_and_discards_done(self):
        raw = b"data:AirB\n\ndata:uds\n\ndata:[DONE]\n\n"
        self.assertEqual(EVAL.parse_sse(raw), "AirBuds")

    def test_sse_errors_are_not_counted_as_answers(self):
        with self.assertRaises(EVAL.SseProtocolError):
            EVAL.parse_sse(b"event: error\ndata: internal failure\n\n")
        with self.assertRaises(EVAL.SseProtocolError):
            EVAL.parse_sse(b'data: {"error":{"message":"private body"}}\n\n')

    def test_transport_error_unwraps_tls_type_without_returning_message(self):
        error = urllib.error.URLError(ssl.SSLError("private credential-like detail"))
        self.assertEqual(EVAL.safe_error_type(error), "SSLError")

    def test_log_evidence_is_scoped_by_offset_query_and_library(self):
        line = "2026 INFO RAG_EVIDENCE " + json.dumps({
            "query": "退款条件", "knowledgeBaseId": 7, "candidates": [{"text": "答案"}]
        }, ensure_ascii=False) + "\n"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "application.log"
            path.write_text("older unrelated entry\n" + line, encoding="utf-8")
            offset = len("older unrelated entry\n".encode())
            self.assertEqual(EVAL.evidence_since(path, offset, "退款条件", 7)["candidates"][0]["text"], "答案")
            self.assertIsNone(EVAL.evidence_since(path, offset, "退款条件", 1))
            self.assertIsNone(EVAL.evidence_since(path, path.stat().st_size, "退款条件", 7))


if __name__ == "__main__":
    unittest.main()
