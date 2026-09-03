# Privacy Policy for MacroPad

**Last Updated: September 3, 2026**

## Overview

MacroPad ("the App") is a macro nutrient tracking application developed by Kevin Roy. This Privacy Policy explains how the App collects, uses, and protects your information.

## Information We Collect

### Data You Provide
- **Nutrition Data**: Daily macro nutrient intake (protein, carbs, fat) that you manually enter
- **Meal Presets**: Saved meal templates you create for quick logging
- **Daily Targets**: Your personal nutrition goals
- **Annotations**: Optional notes you add to daily entries
- **App Settings**: Your preferences such as day reset time and widget configurations

### Data You Provide to the Optional AI Estimator
If you turn on the AI Estimator (off by default, and unusable until you connect it to a
server you run yourself):
- **Photographs** you attach to an estimate — meals, nutrition labels, menus, receipts
- **The text** you write describing what you ate, and any answers you give to follow-up
  questions
- **Your presets, targets and daily totals**, when you use the Planning feature, so it can
  answer questions about your remaining budget

### Data We Do NOT Collect
- Personal identification information (name, email, phone number)
- Location data
- Device identifiers for advertising
- Usage analytics or tracking data
- Health data from other apps or devices

## How Your Data Is Stored

### Local Storage
All your nutrition data is stored locally on your device in a SQLite database in Android's
private app storage. It does not leave your device unless you explicitly turn on a backup
or the AI Estimator.

### Optional Cloud Backup (Dropbox)
If you choose to enable Dropbox sync:
- Your data is uploaded to YOUR personal Dropbox account
- We do not have access to your Dropbox account or your backed-up data
- You can disconnect Dropbox sync at any time from the Settings screen
- Disconnecting removes the App's access but does not delete files already in your Dropbox

### Optional AI Estimator (self-hosted)
The AI Estimator is off by default and cannot be used until you enter the address of a
server **you** run. There is no shared or default server, and we do not operate one.

When it is on and you submit an estimate or a planning question:
- The photos and text described above are sent to your server, over an encrypted
  connection.
- **Your server passes them to Anthropic's API** in order to run the model that produces
  the estimate. Anthropic's handling of that data is governed by their terms and the plan
  you are using, not by this policy.
- Your server keeps the photos and the estimate so the app can show you a history. How
  long it keeps them, and who else can reach it, are settings on your own machine.
- If more than one person shares a server, each person's jobs, planning threads and
  backups are separate and are not readable by the others.

Turning the AI Estimator off in Settings stops all of this. It does not delete what your
own server has already stored — that is yours to manage.

### Optional Server Backup (self-hosted)
If you back up to your own server, a copy of your nutrition data — daily totals, entries,
presets, targets and settings — is stored there, in a location private to you.

## Data Sharing

**We do not sell, trade, or share your data with any third parties.**

Your nutrition data stays on your device unless you choose otherwise — and the only places
it can go are ones you own: your personal Dropbox account, or a server you run yourself. We
have no servers that receive or store your information.

## Data Security

- All local data is stored in Android's private app storage
- Dropbox transfers use HTTPS encryption
- AI Estimator and server backup traffic is encrypted in transit. For a server on your own
  home network, the App trusts only that server's specific certificate, which you transfer
  by scanning its setup code
- **The App does not transmit data to any servers owned or operated by us.** We operate no
  servers. Any server the App talks to is one you set up and control

## Your Rights

You have complete control over your data:
- **Export**: Export all your data as CSV or JSON from Settings
- **Delete**: Uninstalling the App removes all local data
- **Backup Control**: Enable or disable Dropbox sync at any time
- **AI Control**: The AI Estimator is off until you connect your own server, and can be
  turned off again at any time from Settings

## Children's Privacy

The App does not knowingly collect information from children under 13. The App is intended for general audiences interested in nutrition tracking.

## Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be reflected in the "Last Updated" date above. Continued use of the App after changes constitutes acceptance of the updated policy.

## Open Source

MacroPad is open source. You can review the complete source code at:
https://github.com/kevroy314/Macro

## Contact

If you have questions about this Privacy Policy, please open an issue on our GitHub repository:
https://github.com/kevroy314/Macro/issues

---

*This privacy policy is hosted at: https://kevroy314.github.io/Macro/privacy-policy*
