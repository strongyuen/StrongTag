package net.strong.dao.entity.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.strong.dao.Daos;
import net.strong.dao.DatabaseMeta;
import net.strong.dao.TableName;
import net.strong.dao.entity.Entity;
import net.strong.dao.entity.EntityField;
import net.strong.dao.entity.FieldType;
import net.strong.dao.entity.EntityMaker;
import net.strong.dao.entity.EntityName;
import net.strong.dao.entity.ErrorEntitySyntaxException;
import net.strong.dao.entity.Link;
import net.strong.dao.entity.ValueAdapter;
import net.strong.dao.entity.annotation.Column;
import net.strong.dao.entity.annotation.Default;
import net.strong.dao.entity.annotation.Next;
import net.strong.dao.entity.annotation.PK;
import net.strong.dao.entity.annotation.Id;
import net.strong.dao.entity.annotation.Many;
import net.strong.dao.entity.annotation.ManyMany;
import net.strong.dao.entity.annotation.Name;
import net.strong.dao.entity.annotation.One;
import net.strong.dao.entity.annotation.Prev;
import net.strong.dao.entity.annotation.Readonly;
import net.strong.dao.entity.annotation.Table;
import net.strong.dao.entity.annotation.View;
import net.strong.dao.entity.born.Borns;
import net.strong.dao.entity.next.FieldQuery;
import net.strong.dao.entity.next.FieldQuerys;
import net.strong.dao.sql.FieldAdapter;
import net.strong.lang.Lang;
import net.strong.lang.Mirror;
import net.strong.lang.Strings;
import net.strong.lang.segment.CharSegment;
import net.strong.lang.segment.Segment;
import net.strong.log.Log;
import net.strong.log.Logs;

/**
 * This class must be drop after make() be dropped
 * 
 * @author zozoh(zozohtnt@gmail.com)
 * @author Bird.Wyatt(bird.wyatt@gmail.com)
 * 
 */
public class DefaultEntityMaker implements EntityMaker {

	private static final Log log = Logs.getLog(DefaultEntityMaker.class);

	public Entity<?> make(DatabaseMeta db, Connection conn, Class<?> type) {
		Entity<?> entity = new Entity<Object>();
		Mirror<?> mirror = Mirror.me(type);
		entity.setMirror(mirror);

		if (log.isDebugEnabled())
			log.debugf("Parse POJO <%s> for DB[%s]", type.getName(), db.getTypeName());

		// Get @Table & @View
		entity.setTableName(evalEntityName(type, Table.class, null));
		entity.setViewName(evalEntityName(type, View.class, Table.class));

		// Borning
		entity.setBorning(Borns.evalBorning(entity));

		// Check if the POJO has @Column fields
		boolean existsColumnAnnField = isPojoExistsColumnAnnField(mirror);

		// Eval PKs
		HashMap<String, EntityField> pkmap = new HashMap<String, EntityField>();
		PK pk = type.getAnnotation(PK.class);
		if (null != pk) {
			for (String pknm : pk.value())
				pkmap.put(pknm, null);
		}

		// Get relative meta data from DB
		Statement stat = null;
		ResultSet rs = null;
		ResultSetMetaData rsmd = null;
		List<FieldQuery> befores;
		List<FieldQuery> afters;
		try {
			try {
				stat = conn.createStatement();
				rs = stat.executeQuery(db.getResultSetMetaSql(entity.getViewName()));
				rsmd = rs.getMetaData();
			}
			catch (Exception e) {
				if (log.isWarnEnabled())
					log.warn("Table '" + entity.getViewName() + "' doesn't exist.");
			}

			befores = new ArrayList<FieldQuery>(5);
			afters = new ArrayList<FieldQuery>(5);
			// For each fields ...
			for (Field f : mirror.getFields()) {
				// When the field declared @Many, @One, @ManyMany
				Link link = evalLink(db, conn, mirror, f);
				if (null != link) {
					entity.addLinks(link);
				}
				// Then try to eval the field
				else {
					// This POJO has @Column field, but this field not, so
					// ignore it
					if (existsColumnAnnField)
						if (!pkmap.containsKey(f.getName()))
							if (null == f.getAnnotation(Column.class))
								if (null == f.getAnnotation(Id.class))
									if (null == f.getAnnotation(Name.class))
										continue;
					// Create EntityField
					EntityField ef = evalField(db, rsmd, entity, f);
					if (null != ef) {
						// Is it a PK?
						if (pkmap.containsKey(ef.getName())) {
							pkmap.put(ef.getName(), ef);
							if (!(ef.isId() || ef.isName()))
								ef.setType(FieldType.PK);
						}

						// Is befores? or afters?
						if (null != ef.getBeforeInsert())
							befores.add(ef.getBeforeInsert());
						else if (null != ef.getAfterInsert())
							afters.add(ef.getAfterInsert());

						// Append to Entity
						entity.addField(ef);
					}
				}
			} // Done for all fields
		}
		// For exception...
		catch (SQLException e) {
			throw Lang.wrapThrow(e, "Fail to make POJO '%s'", type);
		}
		// Close ResultSet and Statement
		finally {
			Daos.safeClose(stat, rs);
		}

		// Then let's check the pks
		if (pkmap.size() > 0) {
			EntityField[] pks = new EntityField[pkmap.size()];
			for (int i = 0; i < pk.value().length; i++)
				pks[i] = pkmap.get(pk.value()[i]);

			entity.setPkFields(pks);
		}

		// Eval beforeInsert fields and afterInsert fields
		entity.setBefores(befores.toArray(new FieldQuery[befores.size()]));
		entity.setAfters(afters.toArray(new FieldQuery[afters.size()]));

		return entity;
	}

	private ErrorEntitySyntaxException error(Entity<?> entity, String fmt, Object... args) {
		return new ErrorEntitySyntaxException(String.format("[%s] : %s",
															null == entity	? "NULL"
																			: entity.getType()
																					.getName(),
															String.format(fmt, args)));
	}

	private EntityField evalField(	DatabaseMeta db,
									ResultSetMetaData rsmd,
									Entity<?> entity,
									Field field) throws SQLException {
		// Change accessiable
		field.setAccessible(true);
		// Create ...
		EntityField ef = new EntityField(entity, field);

		// Eval field column name
		Column column = field.getAnnotation(Column.class);
		if (null == column || Strings.isBlank(column.value()))
			ef.setColumnName(field.getName());
		else
			ef.setColumnName(column.value());

		int ci = Daos.getColumnIndex(rsmd, ef.getColumnName());

		// @Readonly
		ef.setReadonly((field.getAnnotation(Readonly.class) != null));

		// Not Null
		if (null != rsmd)
			ef.setNotNull(ResultSetMetaData.columnNoNulls == rsmd.isNullable(ci));

		// For Enum field
		if (null != rsmd)
			if (ef.getMirror().isEnum()) {
				if (Daos.isIntLikeColumn(rsmd, ci))
					ef.setType(FieldType.ENUM_INT);
			}

		// @Default
		Default dft = field.getAnnotation(Default.class);
		if (null != dft) {
			ef.setDefaultValue(new CharSegment(dft.value()));
		}

		// @Prev
		Prev prev = field.getAnnotation(Prev.class);
		if (null != prev) {
			ef.setBeforeInsert(FieldQuerys.eval(db, prev.value(), ef));
		}

		// @Next
		Next next = field.getAnnotation(Next.class);
		if (null != next) {
			ef.setAfterInsert(FieldQuerys.eval(db, next.value(), ef));
		}

		// @Id
		Id id = field.getAnnotation(Id.class);
		if (null != id) {
			// Check
			if (!ef.getMirror().isIntLike())
				throw error(entity, "@Id field [%s] must be a Integer!", field.getName());
			if (id.auto()) {
				ef.setType(FieldType.SERIAL);
				// 如果是自增字段，并且没有声明 '@Next' ，为其增加 SELECT MAX(id) ...
				if (null == field.getAnnotation(Next.class)) {
					ef.setAfterInsert(FieldQuerys.create("SELECT MAX($field) FROM $view", ef));
				}
			} else {
				ef.setType(FieldType.ID);
			}
		}

		// @Name
		Name name = field.getAnnotation(Name.class);
		if (null != name) {
			// Check
			if (!ef.getMirror().isStringLike())
				throw error(entity, "@Name field [%s] must be a String!", field.getName());
			// Not null
			ef.setNotNull(true);
			// Set Name
			if (name.casesensitive())
				ef.setType(FieldType.CASESENSITIVE_NAME);
			else
				ef.setType(FieldType.NAME);
		}

		// Prepare how to adapt the field value to PreparedStatement
		ef.setFieldAdapter(FieldAdapter.create(ef.getMirror(), ef.isEnumInt()));

		// Prepare how to adapt the field value from ResultSet
		ef.setValueAdapter(ValueAdapter.create(ef.getMirror(), ef.isEnumInt()));

		return ef;
	}

	private Link evalLink(DatabaseMeta db, Connection conn, Mirror<?> mirror, Field field) {
		try {
			// @One
			One one = field.getAnnotation(One.class);
			if (null != one) { // One > refer own field
				Mirror<?> ta = Mirror.me(one.target());
				Field referFld = mirror.getField(one.field());
				Field targetPkFld = lookupPkByReferField(ta, referFld);
				return Link.getLinkForOne(mirror, field, ta.getType(), referFld, targetPkFld);
			}
			Many many = field.getAnnotation(Many.class);
			if (null != many) {
				Mirror<?> ta = Mirror.me(many.target());
				Field pkFld;
				Field targetReferFld;
				if (Strings.isBlank(many.field())) {
					pkFld = null;
					targetReferFld = null;
				} else {
					targetReferFld = ta.getField(many.field());
					pkFld = lookupPkByReferField(mirror, targetReferFld);
				}

				return Link.getLinkForMany(	mirror,
											field,
											ta.getType(),
											targetReferFld,
											pkFld,
											many.key());
			}
			ManyMany mm = field.getAnnotation(ManyMany.class);
			if (null != mm) {
				// Read relation
				Statement stat = null;
				ResultSet rs = null;
				ResultSetMetaData rsmd = null;
				boolean fromName = false;
				boolean toName = false;
				try {
					stat = conn.createStatement();
					Segment tableName = new CharSegment(mm.relation());
					rs = stat.executeQuery(db.getResultSetMetaSql(TableName.render(tableName)));
					rsmd = rs.getMetaData();
					fromName = !Daos.isIntLikeColumn(rsmd, mm.from());
					toName = !Daos.isIntLikeColumn(rsmd, mm.to());
				}
				catch (Exception e) {
					if (log.isWarnEnabled())
						log.warnf("Fail to get table '%s', '%s' and '%s' "
									+ "will be taken as @Id ", mm.relation(), mm.from(), mm.to());
				}
				finally {
					Daos.safeClose(stat, rs);
				}
				Mirror<?> ta = Mirror.me(mm.target());
				Field selfPk = mirror.getField(fromName ? Name.class : Id.class);
				Field targetPk = ta.getField(toName ? Name.class : Id.class);
				return Link.getLinkForManyMany(	mirror,
												field,
												ta.getType(),
												selfPk,
												targetPk,
												mm.key(),
												mm.relation(),
												mm.from(),
												mm.to());
				// return Link.getLinkForManyMany(mirror, field, mm.target(),
				// mm.key(), mm.from(), mm
				// .to(), mm.relation(), fromName, toName);
			}
		}
		catch (NoSuchFieldException e) {
			throw Lang.makeThrow(	"Fail to eval linked field '%s' of class[%s] for the reason '%s'",
									field.getName(),
									mirror.getType().getName(),
									e.getMessage());
		}
		return null;
	}

	private static Field lookupPkByReferField(Mirror<?> mirror, Field fld)
			throws NoSuchFieldException {
		Mirror<?> fldType = Mirror.me(fld.getType());

		if (fldType.isStringLike()) {
			return mirror.getField(Name.class);
		} else if (fldType.isIntLike()) {
			return mirror.getField(Id.class);
		}
		throw Lang.makeThrow(	"'%s'.'%s' can only be CharSequence or Integer",
								fld.getDeclaringClass().getName(),
								fld.getName());
	}

	private boolean isPojoExistsColumnAnnField(Mirror<?> mirror) {
		for (Field f : mirror.getFields())
			if (null != f.getAnnotation(Column.class))
				return true;
		return false;
	}

	private EntityName evalEntityName(	Class<?> type,
										Class<? extends Annotation> annType,
										Class<? extends Annotation> dftAnnType) {
		Annotation ann = null;
		Class<?> me = type;
		while (null != me && !(me == Object.class)) {
			ann = me.getAnnotation(annType);
			if (ann != null) {
				String v = Mirror.me(annType).invoke(ann, "value").toString();
				if (!Strings.isBlank(v))
					return EntityName.create(v);
			}
			me = me.getSuperclass();
		}
		if (null != dftAnnType)
			return evalEntityName(type, dftAnnType, null);
		return EntityName.create(type.getSimpleName().toLowerCase());
	}
}
