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

