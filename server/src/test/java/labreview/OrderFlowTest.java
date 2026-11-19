package labreview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderFlowTest extends ApiIntegrationTest {
    @Test
    void sessionProtectsReadsAndWritesAndLogoutEndsSession() throws Exception {
        client.get("/api/orders").expect(401);
        var anonymous = client.session();
        assertThat(anonymous.json().get("username").isNull()).isTrue();
        client.call("POST", "/api/login", "username=test-receiver&password=wrong",
                "application/x-www-form-urlencoded").expect(401);
        client.login();
        var csrf = client.csrf;
        client.csrf = null;
        client.post("/api/orders", order(uniqueOrder(), gloves(10))).expect(403);
        client.csrf = csrf;
        var empty = client.get("/api/orders?query=" + uniqueOrder()).expect(200).json();
        assertThat(empty.get("total").asInt()).isZero();
        client.call("POST", "/api/logout", "", null).expect(204);
        assertThat(client.session().json().get("username").isNull()).isTrue();
        client.get("/api/orders").expect(401);
    }

    @Test
    void createsAndFindsTwoLinesByOrderNumberAndStatus() throws Exception {
        client.login();
        var number = uniqueOrder();
        var id = client.post("/api/orders", order(" " + number + " ", gloves(10), filters(5)))
                .expect(201).json().get("id").asLong();
        var detail = client.get("/api/orders/" + id).expect(200).json();
        assertThat(detail.get("orderNo").asString()).isEqualTo(number);
        assertThat(detail.get("supplierName").asString()).isEqualTo("示例实验耗材供应商");
        assertThat(detail.get("createdBy").asString()).isEqualTo("test-receiver");
        assertThat(detail.get("status").asString()).isEqualTo("PENDING");
        var lines = detail.get("lines");
        assertThat(lines.size()).isEqualTo(2);
        assertThat(lines.get(0).get("remainingQty").asInt()).isEqualTo(10);
        assertThat(lines.get(1).get("remainingQty").asInt()).isEqualTo(5);
        client.get("/api/orders/" + id + "/lines/" + lines.get(0).get("id").asLong() + "/receipts")
                .expect(200);
        var page = client.get("/api/orders?query=" + number + "&status=PENDING&page=1&pageSize=1")
                .expect(200).json();
        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(client.get("/api/orders?query=" + number + "&status=PARTIAL").expect(200).json()
                .get("total").asInt()).isZero();
        client.get("/api/orders/9223372036854775807").expect(404);
    }

    @Test
    void rejectsEmptyAndInvalidOrderQuantities() throws Exception {
        client.login();
        var name = uniqueOrder();
        client.post("/api/orders", order(name)).expect(400);
        for (var quantity : new Object[]{0, -1, 1.5}) {
            client.post("/api/orders", order(name, gloves(quantity))).expect(400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE order_no = ?",
                Integer.class, name)).isZero();
    }

    @Test
    void rejectsDuplicateWithinSupplier() throws Exception {
        client.login();
        var name = uniqueOrder();
        client.post("/api/orders", order(name, gloves(10), filters(5))).expect(201);
        client.post("/api/orders", order(" " + name + " ", gloves(10))).expect(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE order_no = ?",
                Integer.class, name)).isEqualTo(1);
    }
}
