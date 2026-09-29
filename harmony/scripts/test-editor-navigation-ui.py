#!/usr/bin/env python3
"""Check that Back closes the current editor/sheet while retaining its page.

Start on Quick Messages (default seed row '继续') or Provider Settings.
Does not enter credentials, save edits, or make provider requests.
"""
import argparse
import importlib.util
from pathlib import Path


spec = importlib.util.spec_from_file_location('modal_ui', Path(__file__).with_name('test-modal-ui.py'))
modal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(modal)


def has_header(device, title):
    # Settings also contains rows named Quick Messages / Providers.
    # Assert the destination title in the phone toolbar, not any matching row.
    return any(node.get('text') == title and modal.bounds(node)[3] < 400 for node in device.dump())


def quick_back(device):
    assert has_header(device, '快捷消息'), 'Start on Quick Messages'
    device.text('继续')
    assert device.contains('编辑快捷消息'), 'Editor did not open'
    device.dump('editor-open')
    device.screenshot('editor-open')
    device.back()
    assert has_header(device, '快捷消息'), 'Back left the page instead of closing the editor'
    assert not device.contains('编辑快捷消息'), 'Back did not close the editor'


def provider_back(device):
    nodes = device.dump('provider-list')
    title = next(node for node in nodes if node.get('text') == '服务商')
    _, top, _, bottom = modal.bounds(title)
    actions = [node for node in nodes if node.get('clickable') == 'true'
               and modal.bounds(node)[0] > modal.bounds(title)[2]
               and modal.bounds(node)[1] <= (top + bottom) // 2 <= modal.bounds(node)[3]]
    assert actions, 'Provider add button not found'
    device.click(actions[0])
    device.dump('template-open')
    device.screenshot('template-open')
    assert device.contains('添加提供商'), 'Template sheet did not open'
    device.back()
    assert has_header(device, '服务商'), 'Back left the page instead of closing the sheet'
    assert not device.contains('添加提供商'), 'Back did not close the sheet'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('case', choices=['quick-back', 'provider-back'])
    parser.add_argument('--target', required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    device = modal.Device(args.target, args.output_dir)
    error = None
    try:
        {'quick-back': quick_back, 'provider-back': provider_back}[args.case](device)
    except (AssertionError, RuntimeError, StopIteration) as failure:
        error = str(failure)
    device.dump('after')
    device.screenshot('after')
    result = ('PASS' if error is None else 'FAIL') + ' ' + args.case + (': ' + error if error else '')
    (args.output_dir / 'result.txt').write_text(result + '\n')
    print(result)
    if error is not None:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
