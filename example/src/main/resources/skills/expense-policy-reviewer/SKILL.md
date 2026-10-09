---
name: expense-policy-reviewer
description: Review employee reimbursement requests against the company's sample expense policy and draft submission notes.
---

# Expense Policy Reviewer

Use this skill when a user asks whether a business expense can be reimbursed, what supporting
materials are missing, or how to write a reimbursement submission note.

This is a sample company policy for examples and demos. Do not present it as legal, tax, or official
finance advice outside this demo.

## Review Workflow

1. Extract each expense item, amount, scenario, time, participants, invoice status, and missing facts.
2. Classify each item as one of:
   - Directly reimbursable
   - Reimbursable after supplementing materials
   - Requires manager or finance approval
   - Not reimbursable under this policy
3. Explain the policy reason for each classification.
4. Ask for missing information only when it affects the classification or submission.
5. Draft a short reimbursement note the employee can paste into the expense system.

## Sample Policy Rules

- A valid invoice is required for every reimbursed expense.
- Ride-hailing or taxi expenses after 21:00 can be reimbursed when the route is work-related.
- Ride-hailing or taxi expenses before 21:00 require manager approval unless they are between an
  airport or railway station and a business destination.
- Client meals are reimbursable up to 200 RMB per person per meal when the attendee list includes
  customer names, company names, and internal participants.
- Meals exceeding 200 RMB per person require manager approval and a business justification.
- Alcohol is not reimbursable unless pre-approved by the business owner and finance.
- Client gifts are reimbursable up to 500 RMB per recipient when the recipient name, company,
  business purpose, and invoice are provided.
- Client gifts above 500 RMB per recipient require prior approval from the department head and
  finance. Without prior approval, classify the item as not reimbursable until approval evidence is
  supplied.
- Personal items, entertainment unrelated to business, and expenses without invoices are not
  reimbursable.

## Response Format

Answer in Chinese with these sections:

1. 预审结论
2. 明细判断
3. 需要补充的信息或审批
4. 可粘贴的报销提交说明

Keep the answer concise and action-oriented.
