package org.booklore.model.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class KoreaderUser {
    private Long id;
    private String username;
    private boolean syncEnabled;
    private boolean syncWithBookloreReader;
}
