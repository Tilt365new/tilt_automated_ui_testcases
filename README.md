º# Tilt 365 – UI Automation Test Suite

End-to-end UI automation framework for **Tilt365**, built with **Java 17**, **Selenium 4**, **TestNG**, and **Maven**.

This repository supports **local development** and **full CI/CD execution** via **Jenkins**, using a **GitHub App** for authentication and an isolated **DigitalOcean automation runner** for Selenium execution.

---

## 🚀 Key Features

- Selenium 4 + Java 17 UI automation
- Page Object Model (POM) with shared `BasePage`
- TestNG suites: Smoke, Regression, Parallel (CI)
- Parallel execution (method-based)
- MailSlurp integration for email-driven flows
- Stripe Checkout automation (API + CLI)
- Environment-based configuration (`.env.local`, env vars, system props)
- Allure reporting (screenshots, logs, attachments)
- Retry & stability helpers
- Jenkins + Docker ready
- Slack notifications

---

## 📂 Project Structure

```
tilt_automated_ui_testcases/
├── pom.xml
├── Jenkinsfile
├── src/
│   └── test/
│       ├── java/
│       │   ├── base/
│       │   ├── pages/
│       │   ├── tests/
│       │   └── utils/
│       └── resources/
│           ├── testng.xml
│           ├── testng-smoke.xml
│           ├── testng-regression.xml
│           └── testng-parallel.xml
├── .env.sample
├── .env.local
├── ci-local.sh
└── ENDPOINTS_SUMMARY.md
```

---

## ⚙️ Local Setup

### Prerequisites
- Java 17+
- Maven 3.8+
- Google Chrome
- (Optional) Stripe CLI
- (Optional) Allure CLI

### Install dependencies
```bash
mvn clean install
```

### Configure environment
```bash
cp .env.sample .env.local
```

Example `.env.local`:
```properties
BASE_URL=https://tilt-dashboard-dev.tilt365.com/
ADMIN_EMAIL=
ADMIN_PASSWORD=

MAILSLURP_API_KEY=
MAILSLURP_INBOX_ID=

STRIPE_SECRET_KEY=sk_test_
```

---

## ▶️ Running Tests

Smoke:
```bash
mvn test -Dsurefire.suiteXmlFiles=testng-smoke.xml
```

Regression:
```bash
mvn test -Dsurefire.suiteXmlFiles=testng-regression.xml
```

Parallel (CI):
```bash
mvn test -Dsurefire.suiteXmlFiles=testng-parallel.xml
```

CI-like local run:
```bash
./ci-local.sh
```

---

## 🔒 MailSlurp

- Fixed inboxes preferred in CI
- Pool slots supported (`MAILSLURP_API_KEY_1..10`)
- **On HTTP 421 (account-level throttling), invalidate the entire MailSlurp account by deleting all API keys and recreating a new pool of 10 keys**
- Optional fallback to create inboxes (configurable)


---

## 💳 Stripe

- Stripe test secret keys supported
- Stripe CLI supported locally
- CI performs Stripe connectivity checks

---

## 🤖 CI/CD – Jenkins

High-level flow:
1. Jenkins authenticates via GitHub App
2. Secrets & MailSlurp preflight checks
3. Automation runner provisioned (DigitalOcean)
4. UI tests executed remotely
5. Reports collected (Allure, JUnit)
6. Automation runner destroyed (cleanup guard)

---

## 🔐 GitHub App Requirements

- GitHub App installed at **organization level**
- Repository explicitly granted to the App
- Minimum permissions:
  - Contents: Read

> Personal access tokens are intentionally avoided for CI.

---

## 📊 Reports

Generate locally:
```bash
mvn allure:serve
```

CI publishes Allure reports, JUnit XML, screenshots, and logs.

---

## 🧯 Troubleshooting

- **Auth errors** → GitHub App missing repo access
- **MailSlurp 404** → Inbox expired
- **Chrome instability** → verify pinned Chrome version in CI

---