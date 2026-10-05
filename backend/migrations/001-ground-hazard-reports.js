// Run explicitly against the intended database with mongosh; never auto-runs on app startup.
// Idempotent create/collMod + named indexes. No records are rewritten or removed.
const collectionName = 'hazard_reports';
const nullableDate = { bsonType: ['date', 'null'] };
const user = {
  bsonType: 'object', required: ['subjectId', 'displayName'],
  properties: { subjectId: { bsonType: 'string', pattern: '\\S' }, displayName: { bsonType: 'string', pattern: '\\S' } },
};
const checklist = {
  bsonType: 'object',
  properties: Object.fromEntries([
    'descriptionSufficientlyDetailed', 'photoRelevant', 'gpsCorrespondsToArea', 'reportingTimeReasonable',
  ].map(key => [key, { bsonType: ['bool', 'null'] }])),
};
const schema = {
  bsonType: 'object',
  required: ['_id', 'reference', 'version', 'schemaVersion', 'reporter', 'source', 'status', 'createdAt', 'updatedAt', 'history'],
  properties: {
    _id: { bsonType: 'string', pattern: '\\S' },
    reference: { bsonType: 'string', pattern: '^HR-[0-9]{4}-[A-Z0-9]+$' },
    version: { bsonType: ['long', 'int'], minimum: 0 },
    schemaVersion: { enum: [1] },
    reporter: {
      bsonType: 'object', required: ['user', 'type'],
      properties: { user, type: { enum: ['CITIZEN', 'COMMUNITY_VOLUNTEER'] } },
    },
    source: { enum: ['MOBILE_APP', 'WEB_PORTAL'] },
    status: { enum: ['DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED'] },
    hazardType: { enum: ['FLOODING', 'RISING_RIVER_LEVEL', 'BLOCKED_ROAD', 'LANDSLIDE_CRACK', null] },
    description: { bsonType: ['string', 'null'], maxLength: 500 },
    location: {
      bsonType: ['object', 'null'], required: ['latitude', 'longitude'],
      properties: {
        latitude: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: -90, maximum: 90 },
        longitude: { bsonType: ['double', 'int', 'long', 'decimal'], minimum: -180, maximum: 180 },
        areaLabel: { bsonType: ['string', 'null'] }, capturedAt: nullableDate,
      },
    },
    photo: {
      bsonType: ['object', 'null'],
      required: ['id', 'storageKey', 'originalFilename', 'contentType', 'sizeBytes', 'sha256', 'uploadedBy', 'uploadedAt'],
      properties: {
        id: { bsonType: 'string', pattern: '\\S' }, storageKey: { bsonType: 'string', pattern: '\\S' },
        originalFilename: { bsonType: 'string', pattern: '^[^/\\\\\\x00-\\x1F\\x7F]+$' },
        contentType: { bsonType: 'string', pattern: '^image/' },
        sizeBytes: { bsonType: ['long', 'int'], minimum: 1 }, sha256: { bsonType: 'string', pattern: '^[a-f0-9]{64}$' },
        uploadedBy: user, uploadedAt: { bsonType: 'date' }, capturedAt: nullableDate,
      },
    },
    clientCapturedAt: nullableDate, createdAt: { bsonType: 'date' }, updatedAt: { bsonType: 'date' }, submittedAt: nullableDate,
    review: {
      bsonType: ['object', 'null'], required: ['officer', 'startedAt', 'checklist'],
      properties: { officer: user, startedAt: { bsonType: 'date' }, checklist, comments: { bsonType: ['string', 'null'] } },
    },
    verification: {
      bsonType: ['object', 'null'], required: ['reference', 'officer', 'decision', 'checklist', 'decidedAt'],
      properties: {
        reference: { bsonType: 'string', pattern: '^RV-[0-9]{4}-[A-Z0-9]+$' }, officer: user,
        decision: { enum: ['VERIFIED', 'REJECTED'] }, checklist,
        comments: { bsonType: ['string', 'null'] }, rejectionReason: { bsonType: ['string', 'null'] }, decidedAt: { bsonType: 'date' },
      },
    },
    history: {
      bsonType: 'array', minItems: 1,
      items: {
        bsonType: 'object', required: ['id', 'type', 'status', 'actor', 'occurredAt'],
        properties: {
          id: { bsonType: 'string', pattern: '\\S' },
          type: { enum: ['DRAFT_CREATED', 'DRAFT_UPDATED', 'EVIDENCE_REPLACED', 'EVIDENCE_REMOVED', 'SUBMITTED', 'REVIEW_STARTED', 'REVIEW_UPDATED', 'VERIFIED', 'REJECTED'] },
          previousStatus: { enum: ['DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED', null] },
          status: { enum: ['DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED'] }, actor: user, occurredAt: { bsonType: 'date' },
        },
      },
    },
  },
};
const absent = field => ({ [field]: null }); // Mongo equality-to-null also matches absent fields.
const validator = { $and: [
  { $jsonSchema: schema },
  { $or: [
    { status: 'DRAFT', ...absent('submittedAt'), ...absent('review'), ...absent('verification') },
    { $and: [
      { status: { $ne: 'DRAFT' }, hazardType: { $type: 'string' }, description: { $regex: '\\S' },
        location: { $type: 'object' }, photo: { $type: 'object' }, submittedAt: { $type: 'date' } },
      { $or: [
        { status: 'SUBMITTED', ...absent('review'), ...absent('verification') },
        { status: 'UNDER_REVIEW', review: { $type: 'object' }, ...absent('verification') },
        { status: 'VERIFIED', review: { $type: 'object' }, 'verification.decision': 'VERIFIED',
          'verification.rejectionReason': null,
          'verification.checklist.descriptionSufficientlyDetailed': true, 'verification.checklist.photoRelevant': true,
          'verification.checklist.gpsCorrespondsToArea': true, 'verification.checklist.reportingTimeReasonable': true },
        { status: 'REJECTED', review: { $type: 'object' }, 'verification.decision': 'REJECTED',
          'verification.rejectionReason': { $type: 'string', $regex: '\\S' } },
      ] },
    ] },
  ] },
] };
if (!db.getCollectionNames().includes(collectionName)) {
  db.createCollection(collectionName, { validator, validationLevel: 'strict', validationAction: 'error' });
} else {
  const result = db.runCommand({ collMod: collectionName, validator, validationLevel: 'strict', validationAction: 'error' });
  if (!result.ok) throw new Error(`Unable to install report validator: ${JSON.stringify(result)}`);
}
const reports = db.getCollection(collectionName);
reports.createIndex({ reference: 1 }, { name: 'uq_report_reference', unique: true });
reports.createIndex({ 'verification.reference': 1 }, {
  name: 'uq_verification_reference', unique: true,
  partialFilterExpression: { 'verification.reference': { $type: 'string' } },
});
reports.createIndex({ 'photo.id': 1 }, {
  name: 'uq_evidence_id', unique: true, partialFilterExpression: { 'photo.id': { $type: 'string' } },
});
reports.createIndex({ 'reporter.user.subjectId': 1, createdAt: -1, _id: 1 }, { name: 'reporter_created' });
reports.createIndex({ status: 1, submittedAt: 1, _id: 1 }, { name: 'review_queue' });
reports.createIndex({ status: 1, hazardType: 1, submittedAt: -1, _id: 1 }, { name: 'status_hazard_submitted' });
print('001-ground-hazard-reports applied; no report data changed.');
