#!/usr/bin/env python3
"""Validate compiled JVM @org.junit.Test methods using javap, without executing Android.

This checks the JUnit4 runner's public/non-static/no-argument/void method
contract, not merely successful Kotlin compilation. Input is a .class file.
"""
import argparse
import json
import re
import subprocess
from pathlib import Path


def validate_javap(text):
    methods = []
    current = []
    for line in text.splitlines():
        if re.match(r'^  (?:public|protected|private).*\([^\n]*\);$', line):
            if current:
                methods.append('\n'.join(current))
            current = [line]
        elif current:
            current.append(line)
    if current:
        methods.append('\n'.join(current))
    tests = []
    errors = []
    for method in methods:
        if not re.search(r'(?m)^\s+org\.junit\.Test(?:\(|\s*$)', method):
            continue
        header = method.splitlines()[0].strip()
        descriptor = re.search(r'(?m)^\s+descriptor:\s*(\S+)', method)
        descriptor = descriptor.group(1) if descriptor else None
        tests.append({'method': header, 'descriptor': descriptor})
        if descriptor != '()V' or not header.startswith('public ') or re.search(r'\bstatic\b', header):
            errors.append(f'JUnit4 @Test must be public non-static ()V: {header}; descriptor={descriptor}')
    if not tests:
        errors.append('no compiled @org.junit.Test methods found')
    return {'tests': tests, 'errors': errors, 'status': 'invalid' if errors else 'ok'}


def inspect_class(class_file, javap):
    result = subprocess.run([javap, '-v', '-p', str(class_file)], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('javap failed: ' + result.stderr)
    return validate_javap(result.stdout), result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--class-file', required=True)
    parser.add_argument('--javap', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--javap-output', required=True)
    args = parser.parse_args()
    report, raw = inspect_class(Path(args.class_file), args.javap)
    Path(args.output).write_text(json.dumps(report, indent=2) + '\n')
    Path(args.javap_output).write_text(raw)
    print(json.dumps(report, indent=2))
    return 0 if report['status'] == 'ok' else 1


if __name__ == '__main__':
    raise SystemExit(main())
