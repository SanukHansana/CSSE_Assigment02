// Explicit, idempotent custom-auth collection/index migration. No seeded accounts or role changes.
const name = 'users';
const validator = { $jsonSchema: {
  bsonType: 'object', required: ['_id', 'email', 'displayName', 'passwordHash', 'roles', 'enabled', 'createdAt'],
  properties: {
    _id: { bsonType: 'string', minLength: 1 },
    email: { bsonType: 'string', minLength: 3, maxLength: 254 },
    displayName: { bsonType: 'string', pattern: '\\S', maxLength: 100 },
    passwordHash: { bsonType: 'string', pattern: '^\\$2[aby]\\$' },
    roles: { bsonType: 'array', minItems: 1, uniqueItems: true,
      items: { enum: ['CITIZEN', 'COMMUNITY_VOLUNTEER', 'DUTY_OFFICER', 'DMC_OFFICER'] } },
    enabled: { bsonType: 'bool' }, createdAt: { bsonType: 'date' },
  },
} };
if (!db.getCollectionNames().includes(name)) {
  db.createCollection(name, { validator, validationLevel: 'strict', validationAction: 'error' });
} else {
  const result = db.runCommand({ collMod: name, validator, validationLevel: 'strict', validationAction: 'error' });
  if (!result.ok) throw new Error('Unable to install user validator');
}
db.getCollection(name).createIndex({ email: 1 }, { name: 'uq_user_email', unique: true });
print('002-custom-auth-users applied; no accounts created or modified.');
