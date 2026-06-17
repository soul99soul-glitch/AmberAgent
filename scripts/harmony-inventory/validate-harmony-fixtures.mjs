#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../..');
const fixturesRoot = path.join(root, 'harmony', 'tests', 'fixtures');

function walk(dir, files = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((left, right) => left.name.localeCompare(right.name))) {
    const absolute = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(absolute, files);
    if (entry.isFile()) files.push(absolute);
  }
  return files;
}

function relative(file) {
  return path.relative(root, file).split(path.sep).join('/');
}

const errors = [];
let checked = 0;

for (const file of walk(fixturesRoot)) {
  const rel = relative(file);
  const text = fs.readFileSync(file, 'utf8');
  if (rel.endsWith('.json')) {
    checked += 1;
    try {
      JSON.parse(text);
    } catch (error) {
      errors.push(`${rel}: ${error.message}`);
    }
  }
  if (rel.endsWith('.jsonl')) {
    for (const [index, line] of text.split(/\r?\n/).entries()) {
      if (!line.trim()) continue;
      checked += 1;
      try {
        JSON.parse(line);
      } catch (error) {
        errors.push(`${rel}:${index + 1}: ${error.message}`);
      }
    }
  }
}

if (errors.length) {
  console.error(`Fixture validation failed (${errors.length} errors):`);
  for (const error of errors) console.error(`- ${error}`);
  process.exit(1);
}

console.log(`Fixture validation passed (${checked} JSON/JSONL records checked).`);
