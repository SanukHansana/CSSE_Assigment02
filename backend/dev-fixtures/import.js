// Explicit mongosh import; never called by application startup or tests.
// Only the dedicated dmc_demo database is permitted. Existing records are never overwritten.
if (db.getName() !== 'dmc_demo') throw new Error('Use a dedicated dmc_demo database for these fictional fixtures.');
const fs = require('fs');
const path = require('path');
const directory = process.env.DMC_FIXTURE_DIRECTORY || 'target/development-fixtures';
const fixtures = EJSON.parse(fs.readFileSync(path.join(directory, 'fixtures.json'), 'utf8'));
for (const [collection, items] of [['users', fixtures.accounts], ['hazard_reports', fixtures.reports]]) {
    for (const item of items) {
        if (!db.getCollection(collection).findOne({_id: item._id})) db.getCollection(collection).insertOne(item);
    }
}
print('Fictional demo fixtures imported into dmc_demo; existing records unchanged.');
