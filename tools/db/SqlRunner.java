import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 SQL 执行器，用于建库、建表与独立核对数据。
 *
 * <p>存在的理由：验证环境没有 psql 客户端，而用一个只读/写 SQL 的小工具
 * 可以让"核对审计记录"这一步独立于被测应用本身 —— 通过应用自己的接口看结果
 * 无法排除"应用报告的结果与实际落库不一致"。
 *
 * <p>用法：
 * <pre>
 *   java SqlRunner &lt;jdbcUrl&gt; &lt;user&gt; &lt;password&gt; &lt;sqlFile&gt;
 *   java SqlRunner &lt;jdbcUrl&gt; &lt;user&gt; &lt;password&gt; -e "select 1"
 * </pre>
 * SQL 文件以 {@code -- @separator} 之外的分号切分，支持多语句顺序执行。
 * 每条语句都会打印结果（查询打印表格，更新打印影响行数）。
 */
public final class SqlRunner {

	private SqlRunner() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 4) {
			System.err.println("用法: SqlRunner <jdbcUrl> <user> <password> (-e \"SQL\" | <sqlFile>) [-tuple]");
			System.exit(2);
		}
		String url = args[0];
		String user = args[1];
		String password = args[2];
		String sql;
		if ("-e".equals(args[3])) {
			if (args.length < 5) {
				System.err.println("-e 需要紧跟 SQL 文本");
				System.exit(2);
			}
			sql = args[4];
		}
		else {
			sql = Files.readString(Path.of(args[3]), StandardCharsets.UTF_8);
		}
		// -tuple：只输出数据行，列之间用制表符分隔。
		// 供验证脚本做断言使用 —— 解析人类可读的表格文本既脆弱又容易误判。
		boolean tupleOnly = false;
		for (String arg : args) {
			if ("-tuple".equals(arg)) {
				tupleOnly = true;
			}
		}

		try (Connection connection = DriverManager.getConnection(url, user, password)) {
			connection.setAutoCommit(true);
			for (String statement : split(sql)) {
				String trimmed = statement.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				if (tupleOnly) {
					runTuple(connection, trimmed);
				}
				else {
					runOne(connection, trimmed);
				}
			}
		}
	}

	/** 只输出数据行，制表符分隔；出错时以 {@code !ERROR} 前缀输出，便于脚本识别。 */
	private static void runTuple(Connection connection, String sql) {
		try (Statement statement = connection.createStatement()) {
			boolean hasResultSet = statement.execute(sql);
			if (!hasResultSet) {
				System.out.println("!UPDATE\t" + statement.getUpdateCount());
				return;
			}
			try (ResultSet rs = statement.getResultSet()) {
				ResultSetMetaData meta = rs.getMetaData();
				int columns = meta.getColumnCount();
				while (rs.next()) {
					StringBuilder line = new StringBuilder();
					for (int i = 1; i <= columns; i++) {
						if (i > 1) {
							line.append('\t');
						}
						Object value = rs.getObject(i);
						line.append(value == null ? "" : String.valueOf(value));
					}
					System.out.println(line);
				}
			}
		}
		catch (SQLException error) {
			System.out.println("!ERROR\t" + error.getMessage());
		}
	}

	private static void runOne(Connection connection, String sql) {
		System.out.println("--- SQL: " + sql.replaceAll("\\s+", " ").trim());
		try (Statement statement = connection.createStatement()) {
			boolean hasResultSet = statement.execute(sql);
			if (hasResultSet) {
				try (ResultSet rs = statement.getResultSet()) {
					printTable(rs);
				}
			}
			else {
				System.out.println("    (影响行数 = " + statement.getUpdateCount() + ")");
			}
		}
		catch (SQLException error) {
			// 不中断后续语句：验证脚本常需要"故意触发错误"来看系统如何反应
			System.out.println("    [SQL 错误] " + error.getMessage());
		}
	}

	private static void printTable(ResultSet rs) throws SQLException {
		ResultSetMetaData meta = rs.getMetaData();
		int columns = meta.getColumnCount();
		List<String[]> rows = new ArrayList<>();
		String[] header = new String[columns];
		for (int i = 1; i <= columns; i++) {
			header[i - 1] = meta.getColumnLabel(i);
		}
		rows.add(header);
		while (rs.next()) {
			String[] row = new String[columns];
			for (int i = 1; i <= columns; i++) {
				Object value = rs.getObject(i);
				row[i - 1] = value == null ? "NULL" : String.valueOf(value);
			}
			rows.add(row);
		}
		int[] widths = new int[columns];
		for (String[] row : rows) {
			for (int i = 0; i < columns; i++) {
				widths[i] = Math.max(widths[i], displayWidth(row[i]));
			}
		}
		for (int r = 0; r < rows.size(); r++) {
			StringBuilder line = new StringBuilder("    ");
			for (int i = 0; i < columns; i++) {
				line.append(pad(rows.get(r)[i], widths[i])).append("  ");
			}
			System.out.println(line.toString().stripTrailing());
			if (r == 0) {
				StringBuilder sep = new StringBuilder("    ");
				for (int i = 0; i < columns; i++) {
					sep.append("-".repeat(widths[i])).append("  ");
				}
				System.out.println(sep.toString().stripTrailing());
			}
		}
		System.out.println("    (" + (rows.size() - 1) + " 行)");
	}

	/** 中文按两列宽计算，避免表格错位。 */
	private static int displayWidth(String value) {
		int width = 0;
		for (int i = 0; i < value.length(); i++) {
			width += value.charAt(i) > 0x2E80 ? 2 : 1;
		}
		return width;
	}

	private static String pad(String value, int width) {
		StringBuilder builder = new StringBuilder(value);
		int current = displayWidth(value);
		while (current < width) {
			builder.append(' ');
			current++;
		}
		return builder.toString();
	}

	/**
	 * 按分号切分语句，跳过字符串字面量中的分号与 {@code --} 行注释。
	 *
	 * <p>刻意不做完整 SQL 解析：本项目用到的语句足够简单，
	 * 而引入解析器会让这个验证工具本身成为需要被验证的对象。
	 */
	private static List<String> split(String sql) {
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inSingleQuote = false;
		boolean inLineComment = false;
		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (inLineComment) {
				if (c == '\n') {
					inLineComment = false;
					current.append(c);
				}
				continue;
			}
			if (!inSingleQuote && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
				inLineComment = true;
				i++;
				continue;
			}
			if (c == '\'') {
				inSingleQuote = !inSingleQuote;
			}
			if (c == ';' && !inSingleQuote) {
				statements.add(current.toString());
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		if (!current.toString().isBlank()) {
			statements.add(current.toString());
		}
		return statements;
	}
}
