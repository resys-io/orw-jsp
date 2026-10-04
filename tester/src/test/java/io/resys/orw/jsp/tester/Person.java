package io.resys.orw.jsp.tester;

/** A model bean without setters, as fixtures can fill fields directly. */
public class Person {
    private String name;
    private int age;

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }
}
