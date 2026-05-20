package com.booksmith.app.controller;

import com.booksmith.app.dto.request.BookRequestDTO;
import com.booksmith.app.dto.response.ProfileResponseDTO;
import com.booksmith.app.service.BookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@CrossOrigin
@RestController
@RequestMapping("/books")
public class BookController {

    private BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public ResponseEntity<?> getAll(Pageable pageable) {
        var response = bookService.booksByAuthor(pageable);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody BookRequestDTO bookRequestDTO) {
        var response = bookService.create(bookRequestDTO);
        return ResponseEntity.ok().body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,@RequestBody BookRequestDTO bookRequestDTO) throws ClassNotFoundException {
        var response = bookService.update(id,bookRequestDTO);
        return ResponseEntity.ok().body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) throws ClassNotFoundException {
        bookService.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
