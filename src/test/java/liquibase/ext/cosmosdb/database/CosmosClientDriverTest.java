package liquibase.ext.cosmosdb.database;

import com.azure.cosmos.ConnectionMode;
import liquibase.exception.DatabaseException;
import liquibase.ext.cosmosdb.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CosmosClientDriverTest {
    static final Pattern DB_CONNECTION_URI_PATTERN = Pattern.compile("^(.+:).+(@.+)$");
    static final String MASTER_KEY = "test_master_key";

    CosmosClientDriver cosmosClientDriver;
    String invalidDbConnectionUri;

    @BeforeEach
    void beforeEach() {
        cosmosClientDriver = new CosmosClientDriver();

        final Properties properties = TestUtils.loadProperties();
        final String dbConnectionUri = properties.getProperty(TestUtils.DB_CONNECTION_URI_PROPERTY);
        final Matcher matcher = DB_CONNECTION_URI_PATTERN.matcher(dbConnectionUri);
        if (matcher.matches()) {
            invalidDbConnectionUri = matcher.group(1) + MASTER_KEY + matcher.group(2);
        }

        Objects.requireNonNull(invalidDbConnectionUri, "Error constructing invalid database connection URI.");
    }

    @Nested
    class when_connect_is_invoked {
        @Test
        void given_a_CosmosClientBuilder_exception_then_a_DatabaseException_is_thrown_with_a_message_not_containing_the_master_key() {
            final CosmosConnectionString cosmosConnectionString = CosmosConnectionString.fromConnectionString(invalidDbConnectionUri);
            final DatabaseException databaseException = assertThrows(DatabaseException.class, () -> cosmosClientDriver.connect(cosmosConnectionString));
            assertThat(databaseException).hasMessageNotContaining(MASTER_KEY);
        }

        @Test
        void given_an_invalid_connectionMode_then_a_DatabaseException_is_thrown() {
            final CosmosConnectionString cosmosConnectionString = CosmosConnectionString.fromJsonConnectionString(
                    "cosmosdb://{\"accountEndpoint\" : \"https://localhost:8080\", \"accountKey\" : \"key\", \"databaseName\" : \"db1\", \"connectionMode\" : \"bogus\"}");
            assertThrows(DatabaseException.class, () -> cosmosClientDriver.connect(cosmosConnectionString));
        }
    }

    @Nested
    class when_resolveConnectionMode_is_invoked {
        @Test
        void given_an_empty_optional_then_null_is_returned() {
            assertThat(CosmosClientDriver.resolveConnectionMode(Optional.empty())).isNull();
        }

        @Test
        void given_gateway_in_any_case_then_GATEWAY_is_returned() {
            assertThat(CosmosClientDriver.resolveConnectionMode(Optional.of("gateway"))).isEqualTo(ConnectionMode.GATEWAY);
            assertThat(CosmosClientDriver.resolveConnectionMode(Optional.of("GATEWAY"))).isEqualTo(ConnectionMode.GATEWAY);
            assertThat(CosmosClientDriver.resolveConnectionMode(Optional.of("Gateway"))).isEqualTo(ConnectionMode.GATEWAY);
        }

        @Test
        void given_direct_then_DIRECT_is_returned() {
            assertThat(CosmosClientDriver.resolveConnectionMode(Optional.of("direct"))).isEqualTo(ConnectionMode.DIRECT);
        }

        @Test
        void given_an_invalid_value_then_an_IllegalArgumentException_is_thrown() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> CosmosClientDriver.resolveConnectionMode(Optional.of("bogus")));
        }
    }
}
