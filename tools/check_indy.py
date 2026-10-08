#!/usr/bin/env python3
"""
Build guard for Gradle-free Kotlin on Android.

Android's runtime cannot execute `invokedynamic` call sites that bootstrap via
java.lang.invoke.LambdaMetafactory. Google's d8 rewrites those at build time,
but this build uses the older `dx`, which does not. The app itself is compiled
with -Xlambdas=class -Xsam-conversions=class, so it has none. A few
kotlin-stdlib methods do use them, though.

This script walks the call graph from every method in the app's classes into
kotlin-stdlib and fails if any reachable method contains `invokedynamic`. It
uses `javap`. Virtual calls only count classes that reachable code instantiates,
and any JDK-interface method (toString, iterator, compare and so on) on such a class
is treated as reachable, because the framework may call it.

Virtual calls are resolved with rapid type analysis: only classes that reachable code
instantiates are considered as receivers.

usage: check_indy.py <app classes dir> <stdlib classes dir> <app package prefix, e.g. io/github/pizzimenti/scootdash/>
"""
import os
import re
import subprocess
import sys
from collections import defaultdict, deque

INVOKE_RE = re.compile(r'\b(invokevirtual|invokestatic|invokespecial|invokeinterface)\b.*//\s*(?:Interface)?Method\s+(\S+)')
FIELD_RE = re.compile(r'\b(getstatic|putstatic|getfield|putfield)\b.*//\s*Field\s+(\S+)')
NEW_RE = re.compile(r'\bnew\b\s+#\d+\s*//\s*class\s+(\S+)')
INDY_RE = re.compile(r'\binvokedynamic\b')
FRAMEWORK_NAMES = {'toString', 'hashCode', 'equals', 'compareTo', 'compare', 'iterator', 'hasNext', 'next',
                   'remove', 'size', 'get', 'contains', 'containsAll', 'isEmpty', 'run', 'call', 'close',
                   'getKey', 'getValue', 'length', 'charAt', 'subSequence', 'listIterator', 'indexOf'}


def strip_generics(s):
    out, depth = [], 0
    for ch in s:
        if ch == '<':
            depth += 1
        elif ch == '>':
            depth -= 1
        elif depth == 0:
            out.append(ch)
    return ''.join(out)


def list_classes(root):
    res = []
    for d, _, files in os.walk(root):
        if 'META-INF' in d.split(os.sep):
            continue
        for f in files:
            if f.endswith('.class') and f != 'module-info.class':
                res.append(os.path.join(d, f))
    return res


def javap(paths):
    out = []
    for i in range(0, len(paths), 400):
        r = subprocess.run(['javap', '-c', '-p', '-s'] + paths[i:i + 400], capture_output=True, text=True)
        out.append(r.stdout)
    return '\n'.join(out)


def parse(text):
    classes = {}  # name -> {'super': str|None, 'ifaces': [..], 'methods': {(n,d): {...}}}
    cur = None
    pending = None  # method name awaiting descriptor
    curm = None
    for line in text.splitlines():
        s = line.strip()
        if not line.startswith(' ') and (' class ' in ' ' + s or ' interface ' in ' ' + s) and s.endswith('{'):
            h = strip_generics(s[:-1])
            m = re.search(r'\b(class|interface)\s+(\S+)(.*)$', h)
            name = m.group(2).replace('.', '/')
            rest = m.group(3)
            sup = None
            ifaces = []
            me = re.search(r'\bextends\s+([^{]*?)(\bimplements\b|$)', rest)
            if me:
                ext = [x.strip().replace('.', '/') for x in me.group(1).split(',') if x.strip()]
                if m.group(1) == 'interface':
                    ifaces += ext
                else:
                    sup = ext[0]
            mi = re.search(r'\bimplements\s+(.*)$', rest)
            if mi:
                ifaces += [x.strip().replace('.', '/') for x in mi.group(1).split(',') if x.strip()]
            cur = {'name': name, 'super': sup, 'ifaces': ifaces, 'methods': {}}
            classes[name] = cur
            curm = None
            pending = None
            continue
        if cur is None:
            continue
        if line.startswith('  ') and not line.startswith('    '):
            # member header
            if s == 'static {};':
                pending = '<clinit>'
            elif '(' in s:
                pre = strip_generics(s.split('(')[0])
                nm = pre.split()[-1] if pre.split() else ''
                if nm.replace('.', '/') == cur['name'] or nm == cur['name'].split('/')[-1].replace('$', '.'):
                    nm = '<init>'
                elif '.' in nm:  # constructor printed with dotted class name
                    if nm.replace('.', '/') == cur['name']:
                        nm = '<init>'
                pending = nm
            else:
                pending = None  # field
            curm = None
            continue
        if s.startswith('descriptor:') and pending is not None:
            desc = s.split(':', 1)[1].strip()
            if desc.startswith('('):
                key = (pending, desc)
                curm = {'calls': [], 'fields': [], 'news': [], 'indy': False}
                cur['methods'][key] = curm
            pending = None
            continue
        if curm is None:
            continue
        if INDY_RE.search(s):
            curm['indy'] = True
        mm = INVOKE_RE.search(s)
        if mm:
            kind, ref = mm.group(1), mm.group(2)
            ref = ref.replace('"', '')
            owner_name, desc = ref.split(':', 1)
            if '.' in owner_name:
                owner, name = owner_name.rsplit('.', 1)
            else:
                owner, name = cur['name'], owner_name
            curm['calls'].append((kind, owner, name, desc))
            continue
        mf = FIELD_RE.search(s)
        if mf:
            ref = mf.group(2)
            owner_name = ref.split(':', 1)[0]
            owner = owner_name.rsplit('.', 1)[0] if '.' in owner_name else cur['name']
            curm['fields'].append(owner)
            continue
        mn = NEW_RE.search(s)
        if mn:
            curm['news'].append(mn.group(1).replace('"', ''))
    return classes


def main():
    app_dir, std_dir, prefix = sys.argv[1], sys.argv[2], sys.argv[3]
    app_paths = list_classes(app_dir)
    std_paths = list_classes(std_dir)
    classes = parse(javap(app_paths))
    app_names = set(classes)
    std = parse(javap(std_paths))
    classes.update(std)

    subclasses = defaultdict(set)
    for n, c in classes.items():
        if c['super']:
            subclasses[c['super']].add(n)
        for i in c['ifaces']:
            subclasses[i].add(n)

    def all_subs(n):
        seen, dq = set(), deque([n])
        while dq:
            x = dq.popleft()
            for y in subclasses.get(x, ()):
                if y not in seen:
                    seen.add(y)
                    dq.append(y)
        return seen

    def resolve_up(owner, name, desc):
        dq, seen = deque([owner]), set()
        while dq:
            c = dq.popleft()
            if c in seen or c not in classes:
                continue
            seen.add(c)
            if (name, desc) in classes[c]['methods']:
                return (c, name, desc)
            if classes[c]['super']:
                dq.append(classes[c]['super'])
            dq.extend(classes[c]['ifaces'])
        return None

    reach = {}
    work = deque()

    def add(key, parent):
        if key is None or key in reach:
            return
        c, n, d = key
        if c not in classes or (n, d) not in classes[c]['methods']:
            return
        reach[key] = parent
        work.append(key)

    def supertypes(c):
        """All supertype names of c (known or not), including c."""
        seen, dq = set(), deque([c])
        while dq:
            x = dq.popleft()
            if x in seen:
                continue
            seen.add(x)
            if x in classes:
                if classes[x]['super']:
                    dq.append(classes[x]['super'])
                dq.extend(classes[x]['ifaces'])
        return seen

    # Rapid type analysis: a virtual call can only land in classes that are instantiated.
    instantiated = set()
    sites = set()            # (owner, name, desc) of virtual/interface calls seen so far
    sup_cache = {}

    def sups(c):
        if c not in sup_cache:
            sup_cache[c] = supertypes(c)
        return sup_cache[c]

    def touch_class(c, parent):
        if c in classes:
            add((c, '<clinit>', '()V'), parent)

    def instantiate(c, parent):
        if c in instantiated or c not in classes:
            return
        instantiated.add(c)
        st = sups(c)
        for (o, n, d) in list(sites):
            if o in st:
                add(resolve_up(c, n, d), parent)
        # the framework may call JDK-interface methods on stdlib objects we hand it
        if c not in app_names:
            for (n, d) in classes[c]['methods']:
                if n in FRAMEWORK_NAMES:
                    add((c, n, d), parent)

    for c in app_names:
        instantiate(c, None)
        for (n, d) in classes[c]['methods']:
            add((c, n, d), None)

    while work:
        key = work.popleft()
        m = classes[key[0]]['methods'][(key[1], key[2])]
        for owner in m['fields']:
            touch_class(owner, key)
        for c in m['news']:
            touch_class(c, key)
            instantiate(c, key)
        for kind, owner, name, desc in m['calls']:
            touch_class(owner, key)
            if kind in ('invokestatic', 'invokespecial'):
                if owner in classes:
                    add(resolve_up(owner, name, desc), key)
                continue
            site = (owner, name, desc)
            if site not in sites:
                sites.add(site)
            for c in list(instantiated):
                if owner in sups(c):
                    add(resolve_up(c, name, desc), key)

    bad = [k for k in reach if classes[k[0]]['methods'][(k[1], k[2])]['indy']]
    std_reached = sorted({k[0] for k in reach if k[0] not in app_names})
    print(f'check_indy: {len(reach)} reachable methods, {len(std_reached)} stdlib classes touched')
    if os.environ.get('CHECK_INDY_VERBOSE'):
        for c in std_reached:
            ms = sorted(k[1] for k in reach if k[0] == c)
            print('   ', c, ms)
    if bad:
        for k in bad:
            path, p = [], k
            while p is not None and len(path) < 12:
                path.append(f'{p[0]}.{p[1]}{p[2]}')
                p = reach[p]
            print('INVOKEDYNAMIC reachable:\n    ' + '\n <- '.join(path))
        sys.exit(1)
    print('check_indy: OK, no reachable invokedynamic')


if __name__ == '__main__':
    main()
