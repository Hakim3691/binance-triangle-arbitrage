const fs = require('fs');
const path = require('path');
const vec = JSON.parse(fs.readFileSync(path.join(__dirname, 'reference-vectors.json'), 'utf8'));
const name = process.argv[2] || 'usdt_eth_btc';
const steps = vec.calculations[name];
steps.forEach((s, i) => {
    console.log(`step ${i}: percent=${s.percent.toPrecision(18)}`);
    console.log(`  a: spent=${s.a.spent.toPrecision(18)} earned=${s.a.earned.toPrecision(18)} delta=${s.a.delta.toPrecision(18)}`);
    console.log(`  b: spent=${s.b.spent.toPrecision(18)} earned=${s.b.earned.toPrecision(18)}`);
    console.log(`  c: spent=${s.c.spent.toPrecision(18)} earned=${s.c.earned.toPrecision(18)}`);
});
