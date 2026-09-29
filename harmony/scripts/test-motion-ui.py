#!/usr/bin/env python3
"""Capture a visible control's transition on HDC; fails if no intermediate frame is sampled.

Precondition: navigate to the screen and leave the target control visible and idle.
Example: python3 harmony/scripts/test-motion-ui.py --text Explorer --name role-open
Run again with --name role-close to test removal. Does not type or save input.
Requires Pillow. Pixel sampling detects abrupt changes, not animation quality or frame rate.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path
import time

from PIL import Image, ImageChops, ImageStat


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    selector = parser.add_mutually_exclusive_group(required=True)
    selector.add_argument('--text')
    selector.add_argument('--id')
    parser.add_argument('--name', required=True)
    parser.add_argument('--keyboard', action='store_true', help='Focus the selected prompt and check keyboard visibility')
    parser.add_argument('--target', default='127.0.0.1:5555')
    parser.add_argument('--output-dir', type=Path, default=Path('.workflow/harmony-motion-review'))
    parser.add_argument('--crop', type=int, nargs=4, help='Pixel region excluding clocks and unrelated content')
    args = parser.parse_args()

    spec = importlib.util.spec_from_file_location('modal_ui', Path(__file__).with_name('test-modal-ui.py'))
    modal = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modal)
    output = args.output_dir / args.name
    device = modal.Device(args.target, output)
    key, value = ('text', args.text) if args.text is not None else ('id', args.id)
    matches = [node for node in device.dump('before') if node.get(key) == value]
    assert matches, f'Missing visible control: {value}'
    left, top, right, bottom = modal.bounds(matches[-1])
    assert right > left and bottom > top, 'Control is clipped'
    if args.keyboard:
        assert matches[-1].get('type') == 'TextArea', 'Select the multiline prompt editor'
        device.click(matches[-1])
        time.sleep(1)
        nodes = device.dump('keyboard')
        device.screenshot('keyboard')
        assert any(node.get('id') == 'KeyboardCanvas' for node in nodes), 'Keyboard did not open'
        field = next(node for node in nodes if node.get(key) == value)
        save = next(node for node in nodes if node.get('text') == '保存')
        result = {'field_bounds': modal.bounds(field), 'field_original': modal.bounds(field, 'origBounds'),
                  'save_bounds': modal.bounds(save), 'save_original': modal.bounds(save, 'origBounds')}
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        assert result['field_bounds'] == result['field_original'], 'Prompt is clipped by keyboard'
        assert result['save_bounds'] == result['save_original'], 'Save is clipped by keyboard'
        device.back()
        assert any(node.get(key) == value for node in device.dump('keyboard-dismissed')), 'Back closed prompt with keyboard'
        print('PASS prompt keyboard; evidence:', output)
        return
    device.screenshot('before')
    frames = []
    start = time.monotonic()
    # Screenshot capture must begin while uiInput is running; it returns after part of the animation.
    with ThreadPoolExecutor(max_workers=1) as pool:
        click = pool.submit(device.hdc, 'shell', 'uitest', 'uiInput', 'click',
                            (left + right) // 2, (top + bottom) // 2)
        for index in range(8):
            remote = f'/data/local/tmp/amber-motion-{index}.jpeg'
            device.hdc('shell', 'snapshot_display', '-f', remote)
            frames.append({'index': index, 'ms': round((time.monotonic() - start) * 1000), 'remote': remote})
        click.result()
    time.sleep(.5)
    device.screenshot('after')
    device.dump('after')
    for frame in frames:
        device.hdc('file', 'recv', frame['remote'], output / f"frame-{frame['index']}.jpeg")

    before = Image.open(output / 'before.jpeg').convert('RGB')
    after = Image.open(output / 'after.jpeg').convert('RGB')
    crop = tuple(args.crop) if args.crop else (0, 350, before.width, before.height - 40)

    def distance(image, other):
        difference = ImageChops.difference(image.crop(crop), other.crop(crop))
        return round(sum(ImageStat.Stat(difference).mean) / 3, 3)

    for frame in frames:
        image = Image.open(output / f"frame-{frame['index']}.jpeg").convert('RGB')
        frame['from_before'] = distance(image, before)
        frame['from_after'] = distance(image, after)
    intermediate = [frame['index'] for frame in frames
                    if frame['from_before'] > 1 and frame['from_after'] > 1]
    result = {'changed': distance(before, after), 'intermediate_frames': intermediate, 'frames': frames}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    assert result['changed'] > 2, 'Control did not visibly change the selected region'
    assert intermediate, 'No intermediate transition frame sampled; inspect frames and capture timing'


if __name__ == '__main__':
    main()
