package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Vendor record stored in the supplier directory.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class Supplier {
    private long id;
    private String name;
    private String phone;
    private String email;
    private int deliveryDays = 3;
    private double reliabilityScore = 95.0;
    private String linkedCategories;
    private LocalDateTime createdAt;

    /**
     * Creates a new supplier entity with default delivery lead time and reliability score.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public Supplier() {
        this.createdAt = LocalDateTime.now();
    }

    /** @return supplier id @author Ehtisham Haider, Syed Ahmed */
    public long getId() { return id; }

    /** @param id supplier id @author Ehtisham Haider, Syed Ahmed */
    public void setId(long id) { this.id = id; }

    /** @return supplier business name @author Ehtisham Haider, Syed Ahmed */
    public String getName() { return name; }

    /** @param name supplier business name @author Ehtisham Haider, Syed Ahmed */
    public void setName(String name) { this.name = name; }

    /** @return supplier phone number @author Ehtisham Haider, Syed Ahmed */
    public String getPhone() { return phone; }

    /** @param phone supplier phone number @author Ehtisham Haider, Syed Ahmed */
    public void setPhone(String phone) { this.phone = phone; }

    /** @return supplier contact email @author Ehtisham Haider, Syed Ahmed */
    public String getEmail() { return email; }

    /** @param email supplier contact email @author Ehtisham Haider, Syed Ahmed */
    public void setEmail(String email) { this.email = email; }

    /** @return average lead time in days @author Ehtisham Haider, Syed Ahmed */
    public int getDeliveryDays() { return deliveryDays; }

    /** @param deliveryDays average lead time in days @author Ehtisham Haider, Syed Ahmed */
    public void setDeliveryDays(int deliveryDays) { this.deliveryDays = deliveryDays; }

    /** @return reliability percentage (0..100) @author Ehtisham Haider, Syed Ahmed */
    public double getReliabilityScore() { return reliabilityScore; }

    /** @param reliabilityScore reliability percentage (0..100) @author Ehtisham Haider, Syed Ahmed */
    public void setReliabilityScore(double reliabilityScore) { this.reliabilityScore = reliabilityScore; }

    /** @return comma-separated product categories supplied @author Ehtisham Haider, Syed Ahmed */
    public String getLinkedCategories() { return linkedCategories; }

    /** @param linkedCategories comma-separated product categories supplied @author Ehtisham Haider, Syed Ahmed */
    public void setLinkedCategories(String linkedCategories) { this.linkedCategories = linkedCategories; }

    /** @return creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** @param createdAt creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return name != null ? name : "Supplier #" + id;
    }
}
