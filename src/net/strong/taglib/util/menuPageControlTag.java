package net.strong.taglib.util;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.WeakHashMap;

import javax.servlet.jsp.JspException;

import net.strong.bean.Constants;
import net.strong.database.weakDbSpecifyValueBean;

import org.apache.struts.util.MessageResources;

public class menuPageControlTag extends menuTag {

	/**
	 *
	 */
	private static final long serialVersionUID = 1L;
	protected ArrayList<Object> menuList = null;

	public int doEndTag() throws JspException {


		//boolean newPool = false;
		menuList = new ArrayList<Object>();  //初始化menuList,此变量用于记录对当前用户没有权限访问的菜单ID
		sql_where =null;
		locale = pageContext.getRequest().getLocale();

//		MessageResources messages =
//		MessageResources.getMessageResources("net.strong.resources.ApplicationResources");
//		String test = messages.getMessage(locale,".index.title");

		xmlPath = pageContext.getServletContext().getRealPath("/WEB-INF/classes/");
		/*    PoolBean pool = (PoolBean )pageContext.getAttribute("pool",this.scope_type);
    if(pool==null)
    {
      //将新生成一个InitAction的方式改为通过单例类获取的方式以减少新对象的生成 2006-07-10
      net.strong.exutil.InitAction initAction = net.strong.exutil.
          singleInitAction.getInitAction(pageContext);
          //new net.strong.exutil.InitAction(pageContext);
//      net.strong.exutil.InitAction initAction = new net.strong.exutil.InitAction(pageContext);
      pool = initAction.getPool();
      log.error("pool is null,create new");
    }
		 */
		if(sqlWhere!=null)
			sql_where = sqlWhere;

		/*    if(isDebug.equalsIgnoreCase("true"))
    {
      ProDebug.addDebugLog("step 2 at menuTag");
      ProDebug.saveToFile();
    }
		 */
		//获取当前用户信息
		net.strong.User user = (net.strong.User) pageContext.getSession().getAttribute(Constants.USER_KEY);
		if(user==null)
		{
			try
			{
				pageContext.getOut().write("<br><b>you have not login<br>please login again!<br>");
			}
			catch(java.io.IOException e)
			{
				throw new JspException("IO Error: " + e.getMessage());
			}
			return (EVAL_PAGE);
		}
		long user_id = user.getUserId();
		long dept_id = user.getDeptId();
		//long role_id = user.getRoleId();
		user = null;

		ResultSet rs = null;
		resultBuf = new StringBuffer();
		dbsBean = new weakDbSpecifyValueBean();
		try
		{
			/*
      if(!pool.isStarted())
      {
        pool.setPath(xmlPath);
        pool.initializePool();
      }
			 */
			con = DriverManager.getConnection(Constants.getProxool_alias_name(pageContext));//pool.getConnection();
			if(pageControl.equalsIgnoreCase("true"))
			{
				if(user_id<=0)
					throw new JspException("没有登录，不能使用");

				Statement t_stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
						ResultSet.CONCUR_READ_ONLY);
				String temp_sql = "select distinct menu_id from bas_page where bas_id not in ( ";
				temp_sql += "select distinct p.bas_id from bas_page p,bas_page_control pc ";
				temp_sql += "where pc.page_id=p.bas_id and pc.readflag=1 and ";
				temp_sql += "(pc.user_id="+String.valueOf(user_id);
				temp_sql += " or (pc.user_id IS NULL and pc.department_id = " + String.valueOf(dept_id) + ") ";
				temp_sql += " or (pc.user_id IS NULL and pc.department_id IS NULL) ) )";
//				ProDebug.addDebugLog("menuTag -- temp_sql :" + temp_sql);
//				ProDebug.saveToFile();

				ResultSet t_rs = t_stmt.executeQuery(temp_sql);
				while(t_rs.next())
				{
					menuList.add(t_rs.getObject(1));  //取出没有权限的菜单的ID
				}
				t_rs.close();
				t_stmt.close();
				t_rs = null;
				t_stmt = null;
			}

			stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
					ResultSet.CONCUR_READ_ONLY);
			String sql = null;
			if(sql_where!=null)
				sql = "select * from " + tableName + " where " + sql_where +
				" and " + parentIdName + "=0 order by BAS_ID";
			else
				sql = "select * from " + tableName + " where " + parentIdName +
				" = 0 order by BAS_ID";
			/*
  if (isDebug.equalsIgnoreCase("true")) {
    ProDebug.addDebugLog("sql at menuTag : " + sql);
    ProDebug.saveToFile();
  }
			 */
			rs = stmt.executeQuery(sql);
			int parentRowCount = 0;
			rs.last();
			parentRowCount = rs.getRow();
			rs.beforeFirst();
			int bas_id[] = new int[parentRowCount];
			int rownum = 0;
			while(rs.next())
			{
				bas_id[rownum] = rs.getInt("BAS_ID");
				rownum++;
			}


			rs.close();
			stmt.close();
			rs = null;
			stmt = null;

			showMenuTree(bas_id,parentRowCount);

			con.close();
		}
		catch(SQLException e)
		{
			CloseCon.Close(con);
			throw new JspException(e.getMessage());
		}
		catch(Exception e1)
		{
			CloseCon.Close(con);
			throw new JspException(e1.getMessage());
		}

		/*    if(newPool)
    {
      pageContext.setAttribute("pool",pool,this.scope_type);
    }

    if(isDebug.equalsIgnoreCase("true"))
    {
      ProDebug.addDebugLog("step 4 at menuTag");
      ProDebug.saveToFile();
    }
		 */
		try
		{
			pageContext.getOut().write(resultBuf.toString());
		}
		catch(java.io.IOException e)
		{
			throw new JspException("IO Error: " + e.getMessage());
		}
		resultBuf = null;

		return (EVAL_PAGE);
	}
	public void showMenuTree(int bas_id[],int rowCount) throws Exception
	{

		if(!con.isClosed())
		{
			con.close(); //先关闭数据库连接
			con = null;
			//con = pool.getConnection(); //重新获取数据库连接
			con = DriverManager.getConnection(Constants.getProxool_alias_name(pageContext));
		}

		for(int j=0;j<rowCount;j++)
		{
			Statement stmt_sub2 = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
					ResultSet.CONCUR_READ_ONLY );
			String sql2 = null;
			if(sql_where!=null)
				sql2 = "select BAS_ID from " + tableName + " where " +parentIdName + "="+
				String.valueOf(bas_id[j])+" and " + sql_where +" order by BAS_ID";
			else
				sql2 = "select BAS_ID from " + tableName + " where " +parentIdName + "="+
				String.valueOf(bas_id[j])+" order by BAS_ID";
			ResultSet rs_sub2 = stmt_sub2.executeQuery(sql2);

//			ProDebug.addDebugLog("sql2 at menuTag : " + sql2);
//			ProDebug.saveToFile();

			int childCount;
			// boolean childEof;
			rs_sub2.last();
			childCount = rs_sub2.getRow();
			rs_sub2.beforeFirst();
			int childRowCount=0;
			int bas_id2[] = new int[childCount];

			while(rs_sub2.next())
			{
				bas_id2[childRowCount] = rs_sub2.getInt("BAS_ID");
				childRowCount++;
			}
			rs_sub2.close();
			stmt_sub2.close();
			rs_sub2 = null;
			stmt_sub2 = null;

			if(childRowCount>0)
			{

				ID++;
				String t_sql = "select * from " + tableName + " where BAS_ID = " + bas_id[j];
				dbsBean.setSql(t_sql);
				WeakHashMap<String, Object> hm = dbsBean.getdbValue(DriverManager.getConnection(Constants.getProxool_alias_name(pageContext)));
				Object ttObj = null;
//				Object targetObj = null;
				//Object hfObj = null;
				String str_msg = null;
				String str_disp = null;
				if(hm!=null)
				{
					ttObj = hm.get("tooltips");
//					targetObj = hm.get("target");
					//hfObj = hm.get(pathFieldName);
					str_msg = ((String)hm.get(msgName)).trim();
					str_disp = ((String)hm.get(dispFieldName)).trim();
					hm.clear();
					hm = null;
				}

//				WeakHashMap hm = dbsBean.getdbValue(xmlPath);
//				Object ttObj = hm.get("tooltips");
				resultBuf.append("<div id=\"main" + ID + "\" class = \"menu\"");
				resultBuf.append(" onclick = \"expandIt('" + ID + "');return false\">");
				resultBuf.append("<table width='100%' border='0' cellspacing='0' cellpadding='0'>");
				resultBuf.append("<tr><td width='35'");
				if(ttObj!=null)
					resultBuf.append(" title='" + ((String)ttObj.toString()).trim() + "' ");
				resultBuf.append("><img src='images/icon-folder1-close.gif' width='15' height='13'>" );
				resultBuf.append("<img src='images/icon-folder-close.gif' width='16' height='15' align='absmiddle'></td>");
				resultBuf.append("<td>");

				if(bMsgFlag)
				{

					MessageResources messages =
						MessageResources.getMessageResources(this.msgResources);
					String msg_value = messages.getMessage(locale,str_msg);

					resultBuf.append(msg_value);
				}
				else
					resultBuf.append(str_disp);

				resultBuf.append("</td>");
				resultBuf.append("</tr></table></div>");
				resultBuf.append("<div id='page"+ (ID++) +"' class='child' style='padding-left:15px'>");
				ID++;

				showMenuTree(bas_id2,childCount);
			}
			else
			{
				boolean bexp = false; //是否此菜单不可见
				if(menuList != null)
				{
					for(int i=0;i<menuList.size();i++)
					{
						Object obj = menuList.get(i);
						int m_id = Integer.valueOf(String.valueOf(obj)).intValue();
						if(m_id == bas_id[j])
						{
							bexp = true;
							break;
						}
					}
					if(bexp)  //不可见，退到下一个菜单
					{
						continue;
					}
				}
				String t_sql = "select * from " + tableName + " where BAS_ID = " + bas_id[j];
				dbsBean.setSql(t_sql);

				WeakHashMap<String, Object> hm = dbsBean.getdbValue(DriverManager.getConnection(Constants.getProxool_alias_name(pageContext)));
				Object ttObj = null;
				Object targetObj = null;
				Object hfObj = null;
				String str_msg = null;
				String str_disp = null;
				if(hm!=null)
				{
					ttObj = hm.get("tooltips");
					targetObj = hm.get("target");
					hfObj = hm.get(pathFieldName);
					str_msg = ((String)hm.get(msgName)).trim();
					str_disp = ((String)hm.get(dispFieldName)).trim();
					hm.clear();
					hm = null;
				}


//				WeakHashMap hm = dbsBean.getdbValue(xmlPath);

//				Object ttObj = hm.get("tooltips");
//				Object targetObj = hm.get("target");
				String str_target = "main";
				if(targetObj!=null)
					str_target = ((String)targetObj.toString()).trim();

				resultBuf.append("<table width='100%' border='0' cellspacing='0' cellpadding='0'> <tr> <td></td>");
				resultBuf.append("<td height='20' " );
				if(ttObj!=null)
					resultBuf.append(" title = '" + ((String)ttObj.toString()).trim() + "'");
				resultBuf.append("><img src='images/icon-folder1-open.gif' width='15' height='13'>");
				resultBuf.append("<img src='images/icon-page.gif' width='16' height='15' align='absmiddle' hspace='4'>");
//				Object hfObj = hm.get(pathFieldName);
				String hf = "";
				if(hfObj!=null)
					hf = ((String)hfObj.toString()).trim();

				resultBuf.append("<a href='" + hf );
				resultBuf.append("?bas_id="+bas_id[j]+"' target='" + str_target + "'>");

				if(bMsgFlag)
				{

					MessageResources messages =
						MessageResources.getMessageResources(this.msgResources);
					String msg_value = messages.getMessage(locale,str_msg);

					resultBuf.append(msg_value);
				}
				else
					resultBuf.append(str_disp);

				resultBuf.append("</a></td> </tr></table>");

			}
		}
		resultBuf.append("</div>");
	}
}