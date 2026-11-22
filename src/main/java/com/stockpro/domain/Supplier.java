package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Vendor record stored in the supplier directory.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
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
     */
    public Supplier() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Returns the supplier database identifier.
     *
     * @return supplier id
     */
    public long getId() { return id; }

    /**
     * Sets the supplier database identifier.
     *
     * @param id supplier id
     */
    public void setId(long id) { this.id = id; }

    /**
     * Returns the supplier business name.
     *
     * @return supplier business name
     */
    public String getName() { return name; }

    /**
     * Sets the supplier business name.
     *
     * @param name supplier business name
     */
    public void setName(String name) { this.name = name; }

    /**
     * Returns the supplier phone number.
     *
     * @return supplier phone number
     */
    public String getPhone() { return phone; }

    /**
     * Sets the supplier phone number.
     *
     * @param phone supplier phone number
     */
    public void setPhone(String phone) { this.phone = phone; }

    /**
     * Returns the supplier contact email.
     *
     * @return supplier contact email
     */
    public String getEmail() { return email; }

    /**
     * Sets the supplier contact email.
     *
     * @param email supplier contact email
     */
    public void setEmail(String email) { this.email = email; }

    /**
     * Returns the average delivery lead time in days.
     *
     * @return average lead time in days
     */
    public int getDeliveryDays() { return deliveryDays; }

    /**
     * Sets the average delivery lead time in days.
     *
     * @param deliveryDays average lead time in days
     */
    public void setDeliveryDays(int deliveryDays) { this.deliveryDays = deliveryDays; }

    /**
     * Returns the supplier reliability score percentage.
     *
     * @return reliability percentage (0..100)
     */
    public double getReliabilityScore() { return reliabilityScore; }

    /**
     * Sets the supplier reliability score percentage.
     *
     * @param reliabilityScore reliability percentage (0..100)
     */
    public void setReliabilityScore(double reliabilityScore) { this.reliabilityScore = reliabilityScore; }

    /**
     * Returns comma-separated product categories supplied by this vendor.
     *
     * @return comma-separated product categories supplied
     */
    public String getLinkedCategories() { return linkedCategories; }

    /**
     * Sets comma-separated product categories supplied by this vendor.
     *
     * @param linkedCategories comma-separated product categories supplied
     */
    public void setLinkedCategories(String linkedCategories) { this.linkedCategories = linkedCategories; }

    /**
     * Returns the creation timestamp.
     *
     * @return creation timestamp
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt creation timestamp
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return name != null ? name : "Supplier #" + id;
    }
}
