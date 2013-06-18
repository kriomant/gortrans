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

---x

CREATE TABLE stopSchedules (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT

	, routeId INTEGER NOT NULL REFERENCES routes(_id) ON DELETE CASCADE
	, stopId INTEGER NOT NULL
	, direction INTEGER NOT NULL
	, scheduleType INTEGER NOT NULL

	, scheduleName TEXT NOT NULL
	, schedule TEXT NOT NULL

	, UNIQUE (routeId, stopId, direction, scheduleType)
);

---x

CREATE TABLE scheduleStops (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT

	, name TEXT NOT NULL UNIQUE
	, stopId INTEGER NOT NULL
);

---x

CREATE TABLE routeGroups (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT

	, name TEXT NOT NULL
);

---x

CREATE TABLE routeGroupItems (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT

	, groupId INTEGER NOT NULL REFERENCES routeGroups(_id) ON DELETE CASCADE
	, routeId INTEGER NOT NULL REFERENCES routes(_id) ON DELETE CASCADE
);

---x

CREATE VIEW routeGroupList AS
SELECT g._id AS _id, g.name AS groupName, r.vehicleType AS vehicleType, r.name AS routeName
FROM routeGroups AS g INNER JOIN routeGroupItems AS i ON g._id = i.groupId INNER JOIN routes AS r ON i.routeId = r._id

---x

CREATE TABLE news (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT
	, externalId TEXT NOT NULL
	, title TEXT NOT NULL
	, content TEXT NOT NULL
	, readMoreLink TEXT
	, loadedAt INTEGER NOT NULL
);

