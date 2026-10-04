package com.acme.shop;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class Order {
    private long id;
    private Customer customer;
    private BigDecimal total;
    private List<OrderLine> lines = new ArrayList<>();

    public long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public List<OrderLine> getLines() {
        return lines;
    }
}
