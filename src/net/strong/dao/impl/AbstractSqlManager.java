package net.strong.dao.impl;

import static java.lang.String.format;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.strong.dao.SqlManager;
import net.strong.dao.SqlNotFoundException;
import net.strong.dao.Sqls;
import net.strong.dao.sql.ComboSql;
import net.strong.dao.sql.Sql;
import net.strong.lang.Lang;
import net.strong.lang.Streams;
import net.strong.lang.Strings;
import net.strong.lang.util.LinkedCharArray;
import net.strong.resource.NutResource;

public abstract class AbstractSqlManager implements SqlManager {

	private Map<String, String> _sql_map;
	private List<String> _sql_keys;

	private Map<String, String> map() {
		if (null == _sql_map)
			this.refresh();
		return _sql_map;
	}

	private List<String> keylist() {
		if (null == _sql_keys)
			this.refresh();
		return _sql_keys;
	}

	public boolean contains(String key) {
		return map().containsKey(key);
	}

	public void saveAs(File f) throws IOException {
		Writer w = Streams.fileOutw(f);
		for (String key : keylist()) {
			w.append("/*").append(Strings.dup('-', 60)).append("*/\n");
			String sql = map().get(key);
			w.append(format("/*%s*/\n", key));
			w.append(sql).append("\n");
		}
		w.flush();
		w.close();
	}

	public String get(String key) {
		String sql = map().get(key);
		if (null == sql)
			throw new SqlNotFoundException(key);
		return sql;
	}

	public Sql create(String key) throws SqlNotFoundException {
		return Sqls.create(get(key));
	}

	public ComboSql createCombo(String... keys) {
		ComboSql combo = new ComboSql();
		if (null == keys || keys.length == 0)
			keys = this.keys();
		for (String key : keys) {
			Sql sql = create(key);
			combo.add(sql);
		}
		return combo;
	}

	public int count() {
		return map().size();
	}

	public String[] keys() {
		return keylist().toArray(new String[keylist().size()]);
	}

	protected void buildSQLMaps(List<NutResource> nrs) {
		_sql_map = new HashMap<String, String>();
		try {
			for (NutResource nr : nrs) {
				Reader r = nr.getReader();
				loadSQL(r);
				r.close();
			}
		}
		catch (IOException e) {
			throw Lang.wrapThrow(e);
		}
	}

	public void addSql(String key, String value) {
		if (map().containsKey(key)) {
			throw Lang.makeThrow("duplicate key '%s'", key);
		}
		key = Strings.trim(key);
		map().put(key, value);
		keylist().add(key);
	}

	static final Pattern ptn = Pattern.compile("(?<=^\n/[*])(.*)(?=[*]/)");

	static class InnerStack {

		LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
		LinkedCharArray list = new LinkedCharArray();
		LinkedCharArray cmts = new LinkedCharArray();
		String key = null;
		boolean inNormalComment;

		void eat(int c) {
			if (inNormalComment) {
				if (cmts.push(c).endsWith("*/")) {
					cmts.clear();
					inNormalComment = false;
				}
			} else if (key != null) {
				if (list.push(c).endsWith("\n/*")) {
					list.popLast(3);
					addOne();
					list.push("\n/*");
				} else if (list.endsWith("/*")) {
					list.popLast(2);
					inNormalComment = true;
				}
			} else {
				if (list.size() < 3) {
					if (!"\n/*".startsWith(list.push(c).toString())) {
						list.clear();
					}
				} else {
					if (list.push(c).endsWith("*/")) {
						Matcher matcher = ptn.matcher(list.popAll());
						if (matcher.find()) {
							key = Strings.trim(matcher.group());
						}
					}
				}
			}
		}

		void addOne() {
			String value = Strings.trim(list.popAll());
			if (!Strings.isBlank(value))
				map.put(key, value);
			key = null;
		}

	}

	static class SqlFileBuilder {
		LinkedHashMap<String, String> map;

		SqlFileBuilder(BufferedReader reader) throws IOException {
			InnerStack stack = new InnerStack();
			int c;
			stack.eat('\n');
			while (-1 != (c = reader.read())) {
				stack.eat(c);
			}
			if (stack.key != null)
				stack.addOne();
			map = stack.map;
			Streams.safeClose(reader);
		}

		Set<String> keys() {
			return map.keySet();
		}

		String get(String key) {
			return map.get(key);
		}
	}

	public void remove(String key) {
		this.keylist().remove(key);
		this.map().remove(key);
	}

	/**
	 * 执行根据流来加载sql内容的操作
	 * 
	 * @param stream
	 * @throws IOException
	 * @author mawenming at 2010-4-10 上午10:04:17
	 */
	private void loadSQL(Reader stream) throws IOException {
		BufferedReader bufferedReader = null;
		try {
			bufferedReader = new BufferedReader(stream);
			SqlFileBuilder p = new SqlFileBuilder(bufferedReader);

			Iterator<String> it = p.keys().iterator();
			_sql_keys = new ArrayList<String>(p.map.size());
			while (it.hasNext()) {
				String key = it.next();
				String value = Strings.trim(p.get(key));
				addSql(key, value);
			}
		}
		finally {
			Streams.safeClose(bufferedReader);
			Streams.safeClose(stream);
		}

	}

}
