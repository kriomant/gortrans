CREATE TABLE IF NOT EXISTS scheduleStops (
	 _id INTEGER PRIMARY KEY AUTOINCREMENT

	, name TEXT NOT NULL UNIQUE
	, stopId INTEGER NOT NULL
);