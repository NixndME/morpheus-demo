#!/usr/bin/env python3
"""Bake the task scripts (app, schema and Grafana files inside) into the plugin resources. Run by build.sh."""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def tgz(folder):
    import base64, io, tarfile
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w:gz') as t:
        for f in sorted(os.listdir(folder)):
            t.add(os.path.join(folder, f), arcname=f)
    return base64.b64encode(buf.getvalue()).decode()


def b64file(path):
    import base64
    return base64.b64encode(open(path, 'rb').read()).decode()


def task_body(file):
    s = open(os.path.join(ROOT, 'tasks', file)).read()
    fills = {'@@SCHEMA@@': lambda: b64file(os.path.join(ROOT, 'app/db/schema.sql')),
             '@@APP@@': lambda: tgz(os.path.join(ROOT, 'app/web')),
             '@@GRAFANA@@': lambda: tgz(os.path.join(ROOT, 'app/grafana')),
             }
    for k, f in fills.items():
        if k in s:
            s = s.replace(k, f())
    return s


# name, file
TASKS = [
    ('Demo Create User', 'user.sh'),
    ('Demo Install MariaDB', 'db.sh'),
    ('Demo Install Web App', 'web.sh'),
    ('Demo Grafana Exporters', 'grafana-db.sh'),
    ('Demo Grafana Server', 'grafana-web.sh'),
    ('Demo OpsRamp Agent', 'opsramp.sh'),
    ('Demo Server Issue', 'issue.sh'),
]


if __name__ == '__main__':
    out = os.path.join(ROOT, 'plugin/src/main/resources/demo/tasks')
    os.makedirs(out, exist_ok=True)
    for _, file in TASKS:
        body = task_body(file)
        if '@@' in body.replace('@@SIZES@@', ''):
            raise SystemExit(f'{file}: placeholder left')
        open(os.path.join(out, file), 'w').write(body)
    print(f'==> {len(TASKS)} task scripts baked into {os.path.relpath(out, ROOT)}')
