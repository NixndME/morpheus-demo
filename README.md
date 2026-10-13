# Morpheus Demo plugin

A Morpheus plugin that sets up a ready to show demo on an HPE VM Essentials (HVM) cluster with one click.

![Morpheus Demo walkthrough](https://github.com/NixndME/morpheus-demo/releases/download/v0.1.28/morpheus-demo-preview.gif)

- Walkthrough video (23 min, waits sped up): [morpheus-demo-walkthrough.mp4](https://github.com/NixndME/morpheus-demo/releases/download/v0.1.28/morpheus-demo-walkthrough.mp4)
- The same recording uncut (41 min): [morpheus-demo-full.mp4](https://github.com/NixndME/morpheus-demo/releases/download/v0.1.28/morpheus-demo-full.mp4)
- Plugin: [morpheus-demo-plugin.jar](https://github.com/NixndME/morpheus-demo/releases/download/v0.1.28/morpheus-demo-plugin.jar)

The video shows: upload the plugin, its settings, Set up, two orders from the catalog (one with OpsRamp, one with
Grafana), the approval, the build, the app page, Grafana and OpsRamp, then a CPU spike, a stopped database and
memory pressure, the alerts, and the fix.

Press **Set up** and you get:

- A catalog item **Demo Two Tier App** with 16 inputs: text, email, radio buttons, drop downs, a drop down read
  live from the Morpheus REST API, a password, a number, a checkbox, and a field that shows only for Production.
- A two tier blueprint: a database VM with MariaDB, then a web VM with Apache and PHP, wired together.
- Prices, a price set and three plans (Small, Medium, Large), so every order shows what it costs.
- Workflows that install the app, and the monitoring the user picked: OpsRamp, Grafana or none.
- An approval policy: every order waits for a Morpheus admin.
- Backups on a schedule, cost tags (owner, approver, cost ID) and an end date, all from the same order.
- A second catalog item **Demo Server Issue** that makes a problem on a demo VM (CPU spike, memory pressure or a
  stopped database) and fixes it again, to show the alerts in OpsRamp or Grafana.
- A dashboard **Demo Apps** on Operations > Dashboard.

The plugin keeps no state. Everything it makes is named `Demo ...`, so Set up can run again, and **Remove**
deletes exactly what it made.

## The app

Open the web VM's IP in a browser. The page shows:

- What the app is, who ordered it, the size, monitoring, backup and cost tags
- How it is connected: your browser, the web tier and the database tier, with IPs and the database answer time
- Who is connected: address, browser, first and last seen, requests
- A trace of every request, with the time spent in the database (click a row for its steps)
- A guest book saved in the database

When the database stops, the page turns red and says **Database down**.

## The dashboard

- Apps running, being built, waiting for approval, VMs, cost per month, open alerts
- Approve or reject waiting orders right there
- Every app with its web and database health. Click a row for both VMs, the workflow steps, the alerts, and
  buttons to open the app, Grafana or OpsRamp, or delete the app
- Cost per month by cost owner and by cost ID, the HVM hosts, how an order is built, and the latest activity

## Monitoring

**Grafana**: Grafana and Prometheus run on the web VM. The **Demo App** dashboard shows the database and VM
state, CPU, memory, queries and network. Alerts: **Database down** (within a minute when MariaDB stops),
**High CPU** (over 80%) and **High memory** (over 85%).

**OpsRamp**: the OpsRamp agent goes on both VMs. The plugin gives every VM the Linux OS monitoring template, so
OpsRamp raises its own CPU and memory alerts, and makes a service map per app (web tier and database tier), so
OpsRamp shows how the app is stacked. The plugin also checks every app every 30 seconds and raises **Database
down** or **Web tier down** in OpsRamp, and clears it when the app is fine again. `qa/opsramp/build-dashboard.js` makes a new **Morpheus Demo** dashboard in OpsRamp; it
never changes other dashboards.

## Install

1. Download the jar from the releases page, or build it: `./build.sh` (JDK 17 and Gradle 8.5, the jar lands in `dist/`).
2. Upload it in Administration > Integrations > Plugins.
3. For OpsRamp, fill in the plugin settings: OpsRamp address, API server, tenant ID, agent key and secret, API key
   and secret, and the address of the Morpheus Demo dashboard in OpsRamp.
4. Give your role the **Morpheus Demo** permission (full to set up, read to see the dashboard).
5. Administration > Integrations > Add > **Morpheus Demo**, open it and press **Set up**.

## What it needs

- Morpheus 9.0 or newer, with an HVM cluster and an HVM network. A cluster with more than one host needs a
  shared datastore marked as image target (NFS is fine).
- The built-in Ubuntu 24.04 KVM image (Morpheus downloads it on the first order).
- Internet access from the VMs (Ubuntu packages, Grafana packages, the OpsRamp agent).

## Show it

1. Order **Demo Two Tier App** from Provisioning > Catalog.
2. Approve it on the Demo Apps dashboard.
3. Watch the database VM, then the web VM, build and run their workflows.
4. Open the web VM's IP.
5. Order **Demo Server Issue** on a demo VM: CPU spike, Memory pressure, or Stop the database on the `-db` VM.
   The alert comes in OpsRamp or Grafana, and a stopped database turns the app red.
6. Order it again with **Fix everything** and the alerts clear.
