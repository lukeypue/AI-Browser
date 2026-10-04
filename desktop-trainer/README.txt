SITE BRAIN WINDOWS TRAINER 1.0.0

Luke, start here:

1. Right-click the ZIP and choose Extract All. Keep the whole folder together.
2. Open the extracted Site-Brain-Trainer folder.
3. Double-click 1-Setup.cmd. It downloads the tools and a practice browser.
   The first setup needs internet and may take several minutes.
4. When it says READY, double-click 2-Start.cmd.
5. A progress dashboard opens. Leave the launcher window open and the laptop
   plugged in. The screen can turn off while the launcher keeps Windows awake.
6. Let it run for about 30 minutes for the first test. Check that workers keep
   completing and saving batches. Then let it run longer if it is working.
7. Click Stop training on the dashboard, or double-click 3-Stop.cmd.
8. Double-click 4-Results.cmd. Send Site-Brain-Training-Results.zip in our chat.

You do not need marketplace passwords, API keys, or a cloud account.
This version uses four practice workers and makes no paid AI calls. It practices
searches, filters, listing details, and getting out of controlled failure cases
using our actual Kotlin brain and shipped browser observation code.

Important: the practice websites are synthetic. This is not browsing KSL,
OfferUp, or Facebook. Passing practice does not prove that real sites work.
Saved practice stays on this computer. It is not automatically imported into
your phone or used to retrain a large AI model. We review the results and proven
skills before making another phone update. Each worker has separate memory;
their results are not automatically merged.

WHAT YOU WILL SEE
- Before / After practice: objective test pass rate, including safe failure cases.
- Saved skills: verified reusable procedures in that worker's practice memory.
- Verified search practice: successful search lessons this session, not general
  intelligence or real-site mastery.
- Computer CPU/memory: overall computer resource use, not just this program.

STOP AND RESUME
Previous completed batches and memory are saved in data/worker-N. Start again
with 2-Start.cmd to reuse them. Stop may interrupt the current batch; earlier
checkpoints remain. Only the latest 20 reports per worker are kept. A worker
stops after two repeated process errors. Check its error message and send results.
Do not close the laptop lid if Windows is configured to sleep when it closes.

TROUBLESHOOTING
- Run files from the extracted folder, not from inside the ZIP viewer.
- Setup stopped: read its message, check internet, then run 1-Setup.cmd again.
- No dashboard: look in the launcher for the local http://127.0.0.1 address.
- Already running: use 3-Stop.cmd or the existing dashboard before starting.
- Damaged memory: keep the old folder for review and extract a fresh trainer copy.
- Put the laptop on a dry hard surface with vents clear. Keep it away from A/C
  condensation. Stop if it overheats or becomes unstable.

First setup uses official Node.js and Eclipse Temurin portable downloads with
SHA-256 checks, then Playwright's browser installer. It does not change the
system PATH, install a service, or change permanent Windows execution settings.
The dashboard listens only on this computer. The training browser cannot reach
real websites: its requests are restricted to intercepted practice pages.

SETTINGS (optional; default is suitable for your 32 GB laptop)
Advanced users may run Run.ps1 with -Workers 1 to reduce load or -Workers 6
after measuring resource use. Maximum allowed is 8. More workers are not proof
of better learning. Start with four and review real results before increasing.
