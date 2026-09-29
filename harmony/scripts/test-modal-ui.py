#!/usr/bin/env python3
"""Focused HDC UI regressions; never saves edits or changes existing configuration.

Start on MCP settings for `mcp`/`mcp-headers`, an open multiline editor for `editor-keyboard`,
a MiniApp rename dialog for `mini-rename`, or a MiniApp runner for `mini-source`.
Uses the simulator by default. Screenshots/layouts are written to --output-dir.
Example: python3 harmony/scripts/test-modal-ui.py editor-keyboard
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time


def bounds(node, key='bounds'):
    return [int(value) for value in re.findall(r'-?\d+', node[key])]


class Device:
    def __init__(self, target, output):
        self.target = target
        self.output = output
        output.mkdir(parents=True, exist_ok=True)

    def hdc(self, *args):
        result = subprocess.run(['hdc', '-t', self.target, *map(str, args)],
                                capture_output=True, text=True, timeout=30)
        output = result.stdout + result.stderr
        if result.returncode or '[Fail]' in output or 'Permission denied' in output:
            raise RuntimeError(output)
        return output

    def dump(self, name='current'):
        remote = '/data/local/tmp/amber-modal-regression.json'
        self.hdc('shell', 'uitest', 'dumpLayout', '-p', remote)
        local = self.output / (name + '.json')
        self.hdc('file', 'recv', remote, local)

        def walk(node):
            yield node.get('attributes', {})
            for child in node.get('children', []):
                yield from walk(child)
        return list(walk(json.loads(local.read_text())))

    def screenshot(self, name):
        remote = '/data/local/tmp/amber-modal-regression.jpeg'
        self.hdc('shell', 'snapshot_display', '-f', remote)
        self.hdc('file', 'recv', remote, self.output / (name + '.jpeg'))

    def click(self, node):
        x1, y1, x2, y2 = bounds(node)
        assert x2 > x1 and y2 > y1, 'Target is clipped'
        self.hdc('shell', 'uitest', 'uiInput', 'click', (x1 + x2) // 2, (y1 + y2) // 2)
        time.sleep(.5)

    def text(self, text):
        matches = [node for node in self.dump() if node.get('text') == text]
        assert matches, f'Missing control: {text}'
        self.click(matches[-1])

    def back(self):
        self.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
        time.sleep(.6)

    def contains(self, text):
        return any(node.get('text') == text for node in self.dump())


def editor_keyboard(device):
    nodes = device.dump('editor-before')
    fields = [node for node in nodes if node.get('type') == 'TextArea']
    assert fields, 'Open the multiline editor before running this case'
    device.click(fields[-1])
    time.sleep(1)
    nodes = device.dump('editor-keyboard')
    device.screenshot('editor-keyboard')
    assert any(node.get('id') == 'KeyboardCanvas' for node in nodes), 'Keyboard did not open'
    save = next((node for node in nodes if node.get('text') == '保存'), None)
    assert save is not None, 'Keyboard hides Save control'
    assert bounds(save) == bounds(save, 'origBounds'), 'Save control is clipped'
    field = next(node for node in nodes if node.get('type') == 'TextArea')
    assert bounds(field) == bounds(field, 'origBounds'), 'Editor is clipped by keyboard'
    assert bounds(field)[3] - bounds(field)[1] >= bounds(save)[3] - bounds(save)[1], 'Editor collapsed'
    device.back()  # keyboard first
    assert device.contains('保存'), 'First back should dismiss keyboard, retaining editor'
    device.back()  # then editor
    assert not any(node.get('type') == 'TextArea' for node in device.dump()), 'Second back did not close editor'


def mcp(device):
    nodes = device.dump()
    assert any(node.get('text') == 'MCP 设置' for node in nodes), 'Start on MCP settings'
    for repeat in range(3):
        nodes = device.dump()
        plus = next((node for node in nodes if node.get('id') == 'mcp-add-server'), None)
        if plus is None:  # baseline build before accessibility ID was introduced
            title = next(node for node in nodes if node.get('text') == 'MCP 设置')
            _, top, _, bottom = bounds(title)
            candidates = [node for node in nodes if node.get('clickable') == 'true'
                          and bounds(node)[1] <= (top + bottom) // 2 <= bounds(node)[3]
                          and bounds(node)[0] > bounds(title)[0]]
            plus = max(candidates, key=lambda node: bounds(node)[0])
        device.click(plus)
        time.sleep(1)
        device.dump(f'mcp-open-{repeat}')
        device.screenshot(f'mcp-open-{repeat}')
        assert device.contains('基础设置'), 'MCP add sheet disappeared after +'
        device.back()
        assert not device.contains('基础设置'), 'Back did not close MCP sheet'
        assert device.contains('MCP 设置'), 'Back incorrectly left MCP page'
    device.text('导入')
    assert device.contains('导入MCP服务器'), 'Import sheet did not open'
    device.text('取消')
    assert device.contains('MCP 设置'), 'Cancel incorrectly left MCP page'


def mini_rename(device):
    device.text('重命名小应用')
    device.dump('rename-title-click')
    device.screenshot('rename-title-click')
    assert device.contains('重命名小应用'), 'Tapping dialog title dismissed rename'
    device.back()
    assert device.contains('小应用'), 'Back left MiniApp list instead of closing rename'
    assert not device.contains('重命名小应用'), 'Rename did not close'


def mcp_headers(device):
    def field(prefix):
        return next(node for node in device.dump() if node.get('id', '').startswith(prefix))

    device.click(field('mcp-add-server'))
    device.text('添加请求头')
    device.click(field('mcp-header-name-'))
    time.sleep(1)
    for char in 'Authorization':
        device.hdc('shell', 'uitest', 'uiInput', 'text', char)
    name = field('mcp-header-name-')
    assert name.get('text') == 'Authorization', 'Header name lost characters'
    assert name.get('focused') == 'true', 'Header name lost focus during typing'
    device.back()
    device.click(field('mcp-header-value-'))
    time.sleep(1)
    device.hdc('shell', "uitest uiInput text 'Bearer '")
    assert field('mcp-header-value-').get('text') == 'Bearer ', 'Intermediate space was trimmed'
    device.hdc('shell', 'uitest', 'uiInput', 'text', 'test-token')
    assert field('mcp-header-value-').get('text') == 'Bearer test-token', 'Header value changed'
    device.dump('mcp-headers')
    device.screenshot('mcp-headers')
    device.back()
    device.back()  # discard test draft


def mini_source(device):
    device.text('⋯')
    assert device.contains('查看源码'), 'More menu failed to open or app crashed'
    device.text('查看源码')
    device.text('源码编辑')
    assert device.contains('源码编辑'), 'Tapping source title dismissed editor'
    device.dump('source-title-click')
    device.screenshot('source-title-click')
    device.back()
    assert not device.contains('源码编辑'), 'Back did not close source editor'
    assert device.contains('⋯'), 'Back incorrectly left MiniApp runner'


def mini_confirm(device):
    device.text('删除小应用')
    assert device.contains('取消'), 'Tapping confirmation title dismissed dialog'
    device.screenshot('delete-confirm')
    device.back()
    assert device.contains('小应用'), 'Closing confirmation crashed or left MiniApp list'
    assert not device.contains('删除小应用'), 'Confirmation did not close'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('case', choices=['mcp', 'mcp-headers', 'editor-keyboard', 'mini-rename', 'mini-source', 'mini-confirm'])
    parser.add_argument('--target', default='127.0.0.1:5555')
    parser.add_argument('--output-dir', type=Path, default=Path(tempfile.mkdtemp(prefix='amber-modal-')))
    args = parser.parse_args()
    device = Device(args.target, args.output_dir)
    cases = {'mcp': mcp, 'mcp-headers': mcp_headers, 'editor-keyboard': editor_keyboard,
             'mini-rename': mini_rename, 'mini-source': mini_source, 'mini-confirm': mini_confirm}
    try:
        cases[args.case](device)
    except (AssertionError, RuntimeError) as error:
        device.dump('failure')
        device.screenshot('failure')
        (args.output_dir / 'result.txt').write_text(f'FAIL {args.case}: {error}\n')
        raise SystemExit(f'FAIL {args.case}: {error}\nEvidence: {args.output_dir}') from error
    (args.output_dir / 'result.txt').write_text(f'PASS {args.case}\n')
    print(f'PASS {args.case}; evidence: {args.output_dir}')


if __name__ == '__main__':
    main()
