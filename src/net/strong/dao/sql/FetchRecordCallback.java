package net.strong.dao.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import net.strong.dao.entity.Record;

public class FetchRecordCallback implements SqlCallback {

	public Object invoke(Connection conn, ResultSet rs, Sql sql) throws SQLException {
		if (null != rs && rs.next()) {
			return Record.create(rs);
		}
		return null;
	}

}
