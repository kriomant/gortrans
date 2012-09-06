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

