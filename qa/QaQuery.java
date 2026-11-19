import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Base64;

class QaQuery {
    private static String encode(String text) {
        return text == null ? "-" : Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        String query = new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8);
        String sql = query.stripLeading();
        if (sql.length() < 7 || !sql.regionMatches(true, 0, "SELECT", 0, 6) || !Character.isWhitespace(sql.charAt(6))) {
            throw new IllegalArgumentException("QA accepts SELECT queries only");
        }
        int port = Integer.parseInt(System.getenv().getOrDefault("IMS_QA_PGPORT", "55483"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid QA database port");
        try (var connection = DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:" + port + "/item_management_db", "postgres", "postgres")) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(query)) {
                for (int i = 1; i < args.length; i++) statement.setLong(i, Long.parseLong(args[i]));
                try (var rs = statement.executeQuery()) {
                    var columns = rs.getMetaData();
                    while (rs.next()) {
                        for (int i = 1; i <= columns.getColumnCount(); i++) {
                            if (i > 1) System.out.print("\t");
                            System.out.print(columns.getColumnLabel(i) + ":" + columns.getColumnTypeName(i) + ":" + encode(rs.getString(i)));
                        }
                        System.out.println();
                    }
                }
            }
        }
    }
}
