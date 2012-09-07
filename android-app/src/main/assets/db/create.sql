-- IMPORTANT: Statements must be separated using triple dash then 'x',
-- because SQLiteDatabase.execSQL doesn't support executing multiple statements with one call.

CREATE TABLE routes (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT
	, vehicleType INTEGER NOT NULL
	, externalId TEXT NOT NULL
	, name TEXT NOT NULL
	, firstStopName TEXT NOT NULL
	, lastStopName TEXT NOT NULL

	, UNIQUE (vehicleType, externalId)
);

---x

CREATE TABLE updateTimes (
	  code TEXT NOT NULL PRIMARY KEY
	, lastUpdateTime INTEGER NOT NULL
);

---x

CREATE TABLE routePoints (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT
	, routeId INTEGER NOT NULL REFERENCES routes(_id) ON DELETE CASCADE
	, idx INTEGER NOT NULL -- 0-based point index within route
	, latitude REAL NOT NULL
	, longitude REAL NOT NULL

	, UNIQUE (routeId, idx)
);

---x

CREATE TABLE foldedRouteStops (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT
	, routeId INTEGER NOT NULL REFERENCES routes(_id) ON DELETE CASCADE
	, name TEXT NOT NULL
	, idx INTEGER NOT NULL -- 0-based point index within route
	, forwardPointIndex INTEGER
	, backwardPointIndex INTEGER

	, UNIQUE (routeId, idx)
);

