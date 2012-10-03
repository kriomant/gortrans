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

