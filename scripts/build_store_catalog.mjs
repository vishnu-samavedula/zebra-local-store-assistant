import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Emits an apply_patch patch; it never writes files directly.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = process.argv[2];
if (!source) throw new Error('Usage: node scripts/build_store_catalog.mjs <source catalog.json>');
const catalog = JSON.parse(fs.readFileSync(source, 'utf8'));
const fragranceNames = {
  'north-01': 'North one',
  'after-rain': 'After Rain',
  'soft-hours': 'Soft Hours',
  'citrus-line': 'Citrus fresh',
  'ember-oud': 'Cedar',
  'iris-paper': 'irish paper',
};
const productName = p => fragranceNames[p.id] ?? (p.title === 'Pace 01 Runner' ? 'Pace Runner' : p.title);
const categories = [...new Set(catalog.products.map(p => p.category))];
const sectionCounts = new Map();
const sections = new Map(catalog.products.map(p => {
  const number = (sectionCounts.get(p.category) ?? 0) + 1;
  sectionCounts.set(p.category, number);
  return [p.id, `${String.fromCharCode(65 + categories.indexOf(p.category))}${number}`];
}));
const slug = value => value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const sizeLabels = { XS: 'extra small', S: 'small', M: 'medium', L: 'large', XL: 'extra large', XXL: 'double extra large', 'S/M': 'small medium', 'L/XL': 'large extra large', 'One size': 'one size' };
const colorLabels = { Ink: 'ink', Cobalt: 'cobalt blue', 'White / Blue': 'white and blue', 'White / Grey': 'white and grey', 'Sand / Navy': 'sand and navy', 'Burgundy / Gum': 'burgundy and gum' };
const spokenSize = size => sizeLabels[size] ?? size.replace(/L$/, ' liters').replace(/ ml$/, ' milliliters');
const variants = [];
catalog.products.forEach((p, productIndex) => {
  const name = productName(p);
  const spokenName = name.replace(/(\d+)L\b/g, '$1 liters').replace(/3-Pack/g, 'three pack').replace(/2-Pack/g, 'two pack').replace(/T-Shirt/g, 'tee shirt');
  // Fragrance volume is not a color. The source has only one volume per fragrance.
  const colors = p.category === 'Fragrance' ? [''] : p.colors;
  colors.forEach((color, colorIndex) => p.sizes.forEach((size, sizeIndex) => {
    const sku = `SKU-${String(1001 + variants.length).padStart(4, '0')}`;
    if (!/^SKU-\d{4}$/.test(sku)) throw new Error('SKU exceeds four-digit capacity');
    const section = sections.get(p.id);
    const bin = colorIndex * p.sizes.length + sizeIndex + 1;
    if (bin > 99) throw new Error('Bin exceeds two-digit capacity');
    const spokenColor = colorLabels[color] ?? color.toLowerCase();
    const sizeLabel = spokenSize(size);
    const spokenLabel = [spokenName, spokenColor, p.category === 'Wallets' ? '' : p.category === 'Backpacks' ? '' : `${p.category === 'Fragrance' ? '' : 'size '}${sizeLabel}`].filter(Boolean).join(', ');
    const onHand = (productIndex * 7 + colorIndex * 11 + sizeIndex * 3) % 41;
    const reserved = Math.min(onHand, (productIndex + colorIndex + sizeIndex) % 6);
    const aliases = [...new Set([name.toLowerCase(), spokenName.toLowerCase(), spokenLabel.toLowerCase(), p.title.toLowerCase(), p.category.toLowerCase(), spokenColor, sizeLabel].filter(Boolean))];
    variants.push({
      product_id: `${p.id}-${color ? slug(color) + '-' : ''}${slug(size)}`,
      parent_product_id: p.id, sku, name, category: p.category, brand: p.brand,
      color, size, unit: ['Sneakers', 'Slides'].includes(p.category) ? 'pair' : p.category === 'Socks' ? 'pack' : p.category === 'Fragrance' ? 'bottle' : 'each',
      section, bin, location: `Section ${section} · Bin ${bin}`,
      on_hand: onHand, reserved, spoken_name: spokenName, spoken_color: spokenColor, spoken_size: sizeLabel, spoken_label: spokenLabel,
      aliases, attributes: { ...p.attributes, material: p.material }, image: p.image,
    });
  }));
});
const replace = (relative, content) => {
  const target = path.join(root, relative);
  const old = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : null;
  if (old === null) return `*** Add File: ${target}\n${content.trimEnd().split('\n').map(l => '+' + l).join('\n')}\n`;
  const before = old.trimEnd().split('\n');
  const after = content.trimEnd().split('\n');
  // Optional fixed-size warehouse patch segment for tool output limits.
  if (relative === 'seed_data/warehouse_catalog.jsonl' && process.argv[3] !== undefined) {
    const first = Number(process.argv[3]);
    if (!Number.isInteger(first) || first < 0 || before.length !== after.length) throw new Error('Invalid patch segment');
    const end = Math.min(first + 64, after.length);
    if (before.slice(first, end).every((line, i) => line === after[first + i])) return '';
    return `*** Update File: ${target}\n@@\n${[...before.slice(Math.max(0, first - 3), first).map(l => ' ' + l), ...before.slice(first, end).map(l => '-' + l), ...after.slice(first, end).map(l => '+' + l)].join('\n')}\n`;
  }
  let start = 0;
  while (start < before.length && start < after.length && before[start] === after[start]) start++;
  if (start === before.length && start === after.length) return '';
  let endBefore = before.length, endAfter = after.length;
  while (endBefore > start && endAfter > start && before[endBefore - 1] === after[endAfter - 1]) { endBefore--; endAfter--; }
  const context = before.slice(Math.max(0, start - 3), start).map(l => ' ' + l);
  return `*** Update File: ${target}\n@@\n${[...context, ...before.slice(start, endBefore).map(l => '-' + l), ...after.slice(start, endAfter).map(l => '+' + l)].join('\n')}\n`;
};
const parentSnapshot = { ...catalog, products: catalog.products.map(p => ({ ...p, title: productName(p), section: sections.get(p.id), ...(p.category === 'Fragrance' ? { colors: [] } : {}) })) };
console.log('*** Begin Patch\n' + replace('seed_data/store_catalog.json', JSON.stringify(parentSnapshot, null, 2) + '\n') + replace('seed_data/warehouse_catalog.jsonl', variants.map(v => JSON.stringify(v)).join('\n') + '\n') + replace('seed_data/warehouse_tasks.jsonl', '') + '*** End Patch');
