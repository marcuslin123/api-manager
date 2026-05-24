# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

This repository is an API manager project configured to run Claude Code via AWS Bedrock.

## Environment Setup

Copy `.env.example` to `.env` and populate the values before running Claude Code:

```bash
cp .env.example .env
```

Required environment variables (see `.env.example`):
- `AWS_BEARER_TOKEN_BEDROCK` — AWS Bedrock bearer token for Claude API access
- `AWS_REGION` — AWS region (default: `us-east-1`)
- `CLAUDE_CODE_USE_BEDROCK` — Set to `1` to route Claude Code through Bedrock

Source the file before running: `source .env`

## Project Structure

The repository is in early setup phase — application code has not been added yet. The `.gitignore` is pre-configured for a JVM-based project (Gradle, Java/Kotlin), suggesting that's the intended stack.

## Tech Stack

- Framework: Spring Boot
- Language: Java