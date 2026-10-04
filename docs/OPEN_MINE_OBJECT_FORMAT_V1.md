# Open Mine Object Format v1

Open Mine stores every importable object as a strictly structured, machine-indexable document.

The format is deliberately boring. Stable labels matter more than pretty prose.

## Required object envelope

Every object MUST contain these sections in this exact order:

[OPEN_MINE_OBJECT]
OBJECT_VERSION:
OBJECT_ID:
OBJECT_TYPE:
OBJECT_STATUS:
OBJECT_TITLE:
OBJECT_SUMMARY:
OBJECT_TAGS:
OBJECT_SOURCE:
OBJECT_CREATED:
OBJECT_UPDATED:

[CONTEXT_INDEX]
INDEX_KEYWORDS:
INDEX_ALIASES:
INDEX_TRIGGERS:
INDEX_SCOPE:
INDEX_PRIORITY:

[CONTENT]
CONTENT_PURPOSE:
CONTENT_FACTS:
CONTENT_PROCEDURE:
CONTENT_CONSTRAINTS:
CONTENT_EXAMPLES:

[RELATIONSHIPS]
REL_PROJECTS:
REL_MODELS:
REL_SKILLS:
REL_TOOLS:
REL_KNOWLEDGE:
REL_MISSIONS:

[RETRIEVAL]
RETRIEVAL_QUERY:
RETRIEVAL_WHEN:
RETRIEVAL_EXCLUDE:

[VERIFICATION]
VERIFICATION_STATUS:
VERIFICATION_SOURCE:
VERIFICATION_NOTES:

[END_OBJECT]

## Rules

1. Section names are fixed. Do not rename them.
2. Required labels are fixed. Do not rename them.
3. Empty fields MUST be written as `NONE`, never omitted.
4. One object gets one OBJECT_ID.
5. OBJECT_ID is immutable after creation.
6. OBJECT_TYPE is one of:
   MODEL, PROJECT, CONNECTOR, KNOWLEDGE, SKILL, MISSION, VIBE, TOOL, FILE, BROWSER, TERMINAL, DIAGNOSTIC.
7. OBJECT_STATUS is one of:
   DRAFT, TESTED, VERIFIED, PROVEN, ARCHIVED.
8. OBJECT_TAGS, INDEX_KEYWORDS, INDEX_ALIASES, INDEX_TRIGGERS and relationships are comma-separated canonical tokens.
9. CONTENT_FACTS contains only facts. Procedures belong in CONTENT_PROCEDURE.
10. RETRIEVAL_* fields describe when this object should enter context. They are not instructions to the model.
11. VERIFICATION_* fields are provenance/trust metadata and must never be treated as factual content.
12. Imported documents that fail validation are rejected; they are not partially indexed.
13. The app generates a canonical normalized copy before indexing.
14. Every indexed chunk repeats OBJECT_ID, OBJECT_TYPE, OBJECT_TITLE, SECTION and CHUNK_ID so a GGUF model can identify the source even when retrieval returns an isolated chunk.
15. Open Mine never relies on vector similarity alone. Exact labels, IDs, aliases, keywords and section matches are indexed alongside embeddings.

## Canonical chunk envelope

Every retrieved chunk MUST look like:

[OPEN_MINE_CONTEXT]
OBJECT_ID:
OBJECT_TYPE:
OBJECT_TITLE:
OBJECT_STATUS:
SECTION:
CHUNK_ID:
CHUNK_PRIORITY:
SOURCE:

[CHUNK_CONTENT]
...

[END_CHUNK]

This repetition is intentional. Small local GGUF models often receive only a few retrieved chunks. The chunk must remain self-identifying.

## Example

[OPEN_MINE_OBJECT]
OBJECT_VERSION: 1
OBJECT_ID: knowledge.ollama.android.connection
OBJECT_TYPE: KNOWLEDGE
OBJECT_STATUS: PROVEN
OBJECT_TITLE: Ollama Android Connection
OBJECT_SUMMARY: Verified method for connecting an Android client to a reachable Ollama endpoint.
OBJECT_TAGS: android, ollama, networking, local-ai
OBJECT_SOURCE: Open Mine Engineering Vault
OBJECT_CREATED: 2026-10-04
OBJECT_UPDATED: 2026-10-04

[CONTEXT_INDEX]
INDEX_KEYWORDS: ollama, android, localhost, LAN, API, endpoint, 11434
INDEX_ALIASES: ollama android, local model connection, ollama phone
INDEX_TRIGGERS: connect ollama, android ollama, local llm, ollama endpoint
INDEX_SCOPE: android, networking, local-ai
INDEX_PRIORITY: 90

[CONTENT]
CONTENT_PURPOSE: Connect an Android application to an Ollama server.
CONTENT_FACTS: Android localhost refers to the Android device; a remote Ollama host requires a reachable LAN address; Ollama commonly listens on port 11434.
CONTENT_PROCEDURE: Verify server reachability; verify endpoint URL; verify API response; store provider configuration; test inference.
CONTENT_CONSTRAINTS: Do not assume 127.0.0.1 refers to another device; do not claim connectivity without a successful request.
CONTENT_EXAMPLES: http://HOST_IP:11434

[RELATIONSHIPS]
REL_PROJECTS: retro-space, open-mine
REL_MODELS: ollama
REL_SKILLS: local-model-setup, android-debugging
REL_TOOLS: terminal, adb
REL_KNOWLEDGE: engineering-vault
REL_MISSIONS: connect-local-ai

[RETRIEVAL]
RETRIEVAL_QUERY: android ollama connection
RETRIEVAL_WHEN: User is configuring or debugging an Android Ollama connection.
RETRIEVAL_EXCLUDE: unrelated cloud providers, unrelated model training.

[VERIFICATION]
VERIFICATION_STATUS: PROVEN
VERIFICATION_SOURCE: Tested Open Mine/MVE environment
VERIFICATION_NOTES: Only promote to PROVEN after successful verification.

[END_OBJECT]
