#!/usr/bin/env bash
set -euo pipefail

# Move to project root (4 levels up from src/test/java/scripts)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
cd "$REPO_ROOT"
echo "[ci-local] Running from project root: $REPO_ROOT"


# Copy envs from your .env or export manually before running:

export ADMIN_USER=erodriguez+a@effectussoftware.com
export ADMIN_PASS=Password#1
export BASE_URL=https://tilt-dashboard-dev.tilt365.com/
export STRIPE_TEST_SECRET_KEY=rk_test_40W8vi2ajcXnxpjHnMcNtfePyfHiIfhP8K7DTGUcMwfbVrMqky5BTlaWDzoDhorIHlFTaM5rV0F1rh8P9fW9UliXx00lfDit7Pq
export STRIPE_PUBLISHABLE_KEY=pk_test_40W8vi2ajcXnxpjHnMcNtdof2NpVc0vlzdpaDvaCEC4McUeiSO6AGItrEOdI8hnOYACqHUyYVyYbInMapgUHoDOgi008dvELtA5

export CI=true
export CHROME_MAJOR_PIN="${CHROME_MAJOR_PIN:-142}"
export CI_EXPLICIT_WAIT_SEC="${CI_EXPLICIT_WAIT_SEC:-60}"
#export MAILSLURP_EXPECTED_FP="${MAILSLURP_EXPECTED_FP:-579d2267880c}"


export MAILSLURP_API_KEY="sk_bE38sfppacW57FbV_drohkciaQ7xQ7jyc2MifAsfuY8qOh7JCMFQ0THJ659lHlW1n6TmqB2HeTHbs7ZKA"
export MAILSLURP_INBOX_ID="f079f520-6d37-4847-942b-d59e054b0890"

# Account 1
export MAILSLURP_API_KEY_1="sk_240Ad704PReLKdzb_40YoxCz3kuCFkjpVrXFPE6o8YOFFkkZxtqrHZkjvi93ARb4QNWppRGVwk0N3cwjd"
export MAILSLURP_INBOX_ID_1="70e7b82d-25c7-449a-a022-3ea8d4d5d97a"

# Account 2
export MAILSLURP_API_KEY_2="sk_m7721f8z4F7b0All_FG03N0F8fhMSzRDmDZZ5d5TcQQSZeoT9d6dTyBmjTv00FlMd6fagbanNTIzPBX6b"
export MAILSLURP_INBOX_ID_2="306efbb3-761f-49fa-9606-90cd3a03dd3a"

# Account 3
export MAILSLURP_API_KEY_3="sk_ph1hkb3mUfGTk6FS_YWu3WVyMGRHL0NmwvgGsaRHUpoFHdsbMmO2Ev0qAbbTCfY4K3lFdLarPwHOfMzIy"
export MAILSLURP_INBOX_ID_3="873f2cac-7ac7-442a-8a19-c094e3891f2f"

# Account 4
export MAILSLURP_API_KEY_4="sk_3gvKBX2OlzixBqMJ_GfeVe15iFMf5Ekwmzf1RBNgmRnKg8KQ98AA9VO8k3om4UvMJcMWWXLxFSeZoYLRP"
export MAILSLURP_INBOX_ID_4="46a7d71d-86b6-4278-8f0f-9d3a53f8bd78"

# Account 5
export MAILSLURP_API_KEY_5="sk_mrdFKka5LWy1pf1C_TEwf4HlqR2gnISW6yoC6hQOUvsvpQvSs09QVbEFWYlx6lhpNmTOAUN0snvEy3gHR"
export MAILSLURP_INBOX_ID_5="62c16691-f61a-4267-8232-cd1fe399cd5f"

# Account 6
export MAILSLURP_API_KEY_6="sk_u59mdkCozXjisYPO_IV9et3rHoCmVETVWJSR0XLcP5SRwql6HE2bzc6BFPbPAkgzTrxPPXBX8qzt9FbEg"
export MAILSLURP_INBOX_ID_6="467a873d-95c4-4a41-8f6d-5dfbdbbaa664"

# Account 7
export MAILSLURP_API_KEY_7="sk_GP38kTcJyCEuwnnL_wqht1Aj14Lno45Ig6QnXtHnEWD39851kJboZn8A5chg0WAaLZFERZGqIwC8Ju4M9"
export MAILSLURP_INBOX_ID_7="271fea91-85ce-4ee0-8d8e-339916ba9444"

# Account 8
export MAILSLURP_API_KEY_8="sk_SEgoc5BMPWQnKhjK_V9ZizuxunUC3ouGLd0kNVnnNSPWEqaZipEkzIEPIkT616H1x6r10mTqqCjM7bMjH"
export MAILSLURP_INBOX_ID_8="f33a06ad-d769-4cf5-a85c-6df252168031"

# Account 9
export MAILSLURP_API_KEY_9="sk_U223z9UUPwhqv8q5_2ssE1ebC0KLXvcJgmYP1H3DNX8yf3qzCX4OPBdShGDcoyjUxGAoCy3hqAbvFuy2s"
export MAILSLURP_INBOX_ID_9="9b42b65f-1826-4ab5-91cd-8d72e3994072"

# Account 10
export MAILSLURP_API_KEY_10="sk_wSyEeW85WnfPcFG5_QFTQjxeJBWEv8W0b5Uz6Usl6giQc0is6aLujgTTaKo6h3oXAlUc0s0rdzNpKgLED"
export MAILSLURP_INBOX_ID_10="30e39d4b-5d1f-45ce-a049-5e8ad7d3ede9"






# Optional: reproduce Jenkins timeout etc.


mvn -B \
  -Dheadless=true -Dbrowser=chrome -DskipITs=false \
  -Dsurefire.suiteXmlFiles=testng-parallel.xml \
  -Dmailslurp.debug=true \
  -DdisableLocalConfig=true \
  -DbaseUrl="$BASE_URL" \
  -DADMIN_USER="$ADMIN_USER" \
  -DADMIN_PASS="$ADMIN_PASS" \
  -Dtimeout="$CI_EXPLICIT_WAIT_SEC" \
  -Dretry=1 \
  -DMAILSLURP_ALLOW_CREATE_INBOX_FALLBACK=true \
  clean test




#  -Dmailslurp.forceKey="$MAILSLURP_API_KEY" \
#  -Dmailslurp.apiKey="$MAILSLURP_API_KEY" \
#  -DMAILSLURP_INBOX_ID="$MAILSLURP_INBOX_ID" \
#  -Dmailslurp.expectedFingerprint="$MAILSLURP_EXPECTED_FP" \







# Account 1 - emilianorod14@op.xn--yaho-sqa.com, Password#1
# Account 2 - emilianorod15@op.xn--yaho-sqa.com, Password#1
# Account 3 - emilianorod16@op.xn--yaho-sqa.com, Password#1
# Account 4 - emilianorod17@op.xn--yaho-sqa.com, Password#1
# Account 5 - emilianorod18@op.xn--yaho-sqa.com, Password#1
# Account 6 - emilianorod19@op.xn--yaho-sqa.com, Password#1
# Account 7 - emilianorod20@op.xn--yaho-sqa.com, Password#1
# Account 8 - emilianorod21@op.xn--yaho-sqa.com, Password#1
# Account 9 - emilianorod22@op.xn--yaho-sqa.com, Password#1
# Account 10 - emilianorod23@op.xn--yaho-sqa.com, Password#1