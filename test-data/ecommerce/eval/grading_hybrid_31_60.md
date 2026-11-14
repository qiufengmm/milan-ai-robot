# Grading: rows 31–60 (B01–E18)

Judgment is per mode. `retrievalHit` uses relevant evidence among `finalDocuments` (the prompt top 3); `candidateHit` records whether relevant evidence appeared in the fused candidate pool (top 10 for hybrid, original top 3 for the vector baseline). Rows marked `N/A` are excluded from retrieval and candidate hit rates. JSON `evidenceIds` lists final top-3 document IDs and titles.

## Totals

| Mode | Answer grades | Final top-3 retrieval (25 applicable) | Candidate pool (25 applicable) |
|---|---|---|---|
| Hybrid | 23 correct, 7 partially correct, 0 incorrect, 0 no_answer | 24 yes, 1 no, 5 N/A | 25 hit, 0 miss |
| Vector baseline | 24 correct, 6 partially correct, 0 incorrect, 0 no_answer | 25 yes, 0 no, 5 N/A | 25 hit, 0 miss |

N/A rows in both modes: C02, C03, D01, E16, E18. D02, D03, D04 and E17 remain retrieval-applicable: although their requested live actions are not integrated, the questions also require static refund/address/contact knowledge that exists in the KB. E16 is an intentional knowledge-gap question, while E18 asks for live stock and dispatch data; neither has a meaningful retrieval target. Their answer safety is still graded.

## Notable misses and limitations

- **C01 (monitor availability):** Hybrid candidates contain the category-list document, but it did not reach final top 3. The answer avoids asserting that monitors are not sold, but omits the four documented categories (partial). Vector baseline has the catalog chunk in its final top 3, which lists the four categories; its answer is grounded and correct.
- **E16 (gift wrapping):** The knowledge base has no gift-wrapping policy, so retrieval is N/A. Both modes avoid inventing a policy but receive a partial answer grade because their next-step guidance is incomplete.
- **D02/D03/D04/E17:** These include unavailable operations, but have retrievable static guidance (refund process, address-change policy, and human contact details). They are included in retrieval scoring; a hit does not mean the operation is implemented.
- **E18 (live stock and dispatch):** Static K87 Pro specifications do not establish current inventory or same-day dispatch, so retrieval is N/A and the answer is judged on the capability boundary.
- **E09:** Hybrid omits that coupon discounts are excluded from invoice amount; vector includes it.
- **E11:** Both correctly distinguish the ¥15 remote-area surcharge from the 2–3 day added delay, but omit the expected approximate 5–7 day total remote-area delivery estimate.
- **B01/B02/B03/E15:** Partial grades reflect omitted model choices or clarifying details, or extra product claims that are unsupported or too broad; see per-row rationales in JSON.
- This report covers exactly the last 30 rows by file order, **B01–E18**. Scores are manual judgments of saved expected answers and saved result evidence; no model APIs were called during grading. `retrievalHit` uses relevant evidence among final top 3; `candidateHit` uses relevant evidence among fused candidates (top 10 for hybrid, top 3 for the vector baseline).
