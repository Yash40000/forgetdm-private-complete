# SWIFT MT message masking (RFP §3.3)

Structure-preserving masking for SWIFT MT (FIN) messages stored in a column or blob — mask the
sensitive parts of a payment/treasury message but keep it a **valid, parseable message**.

## What it does

A FIN message is a sequence of blocks `{1:...}{2:...}{3:...}{4:...-}{5:...}`. Block 4 is the text
block of tagged fields (`:20:`, `:32A:`, `:50K:`, `:59:`, …). `SwiftMtCodec` parses that structure
and repacks it with the **exact same blocks, tags and layout**, transforming only sensitive tokens:

| Token | Masked as | Kept valid by |
|---|---|---|
| Sender/receiver BIC (blocks 1 & 2) + institution BICs (`:52a`–`:58a`) | valid masked BIC | `SWIFT_BIC` (country preserved, length preserved) |
| Account numbers (`/...` lines in `:50a`/`:59a`) | length-preserving | `BANK_ACCOUNT` / `IBAN` (mod-97) |
| Party names (first line of `:50a`/`:59a`) | realistic name | `FULL_NAME` |
| References (`:20:`, `:21:`) | format-preserving | `FORMAT_PRESERVE` |

**Amounts, value dates, currencies and operation codes (`:32A:`, `:23B:`, `:71A:`, narrative
`:70:`) are left intact**, so the message stays financially coherent. All masking is deterministic
(reuses the engine's field maskers), so the same BIC/account masks identically across messages —
referential integrity holds, and it composes with the Vault-held key.

## Using it

It's a first-class masking function: **`SWIFT_MT`**. It shows up in Masking Studio (Financial
category), the policy rule builder, and the Design Catalogue like any other function. Point a rule
at a column that holds MT messages; non-SWIFT values pass through untouched.

- **Studio preview:** pick `SWIFT_MT` → the sample value is a full MT103 in a multi-line editor →
  Preview shows the masked message side-by-side.
- **API:** `POST /api/policies/preview { "function":"SWIFT_MT", "value":"<the message>" }`.

## Scope

- Covers the common structure (blocks 1/2 headers, block 4 fields) and the sensitive tags that
  appear across MT103 / MT202 / MT300-family messages. Block 3/5 control/trailer are left intact.
- ISO 20022 (XML) messages are already handled by the existing JSON/XML unstructured masker; a
  dedicated ISO field-policy is a natural follow-on.

## Tests

`SwiftMtMaskingTest` proves: structure + financial fields preserved, identifiers/PII removed,
account/BIC formats kept, deterministic output, and non-SWIFT passthrough.
Run: `mvn test -Dtest=SwiftMtMaskingTest`.
