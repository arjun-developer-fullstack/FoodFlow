// FoodFlow: one MongoDB database + one user per service (database-per-service).
// Passwords come from the container environment (infra/docker/.env), never from this file.

function requireEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Environment variable ${name} must be set (see infra/docker/.env.example)`);
  }
  return value;
}

db.getSiblingDB('menu_db').createUser({
  user: 'menu_svc',
  pwd: requireEnv('MENU_DB_PASSWORD'),
  roles: [{ role: 'readWrite', db: 'menu_db' }],
});

db.getSiblingDB('notification_db').createUser({
  user: 'notification_svc',
  pwd: requireEnv('NOTIFICATION_DB_PASSWORD'),
  roles: [{ role: 'readWrite', db: 'notification_db' }],
});
