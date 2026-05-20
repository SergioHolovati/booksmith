package com.booksmith.app.service.impl;

import com.booksmith.app.dto.config.UserContextDTO;
import com.booksmith.app.dto.request.BookRequestDTO;
import com.booksmith.app.dto.response.BookResponseDTO;
import com.booksmith.app.dto.response.ChapterResponseDTO;
import com.booksmith.app.enums.BookStatusEnum;
import com.booksmith.app.model.Author;
import com.booksmith.app.model.Book;
import com.booksmith.app.model.Chapter;
import com.booksmith.app.repository.BookRepository;
import com.booksmith.app.service.AuthorService;
import com.booksmith.app.service.BookService;
import com.booksmith.app.service.LocalStorageService;
import com.booksmith.app.utils.SlugUtils;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class BookServiceImpl implements BookService {

    private BookRepository bookRepository;
    private UserContextDTO userContextDTO;
    private AuthorService authorService;
    private LocalStorageService localStorageService;
    private String S3_COVERBOOK_PATH = "book/book-%s-cover.%s";

    public BookServiceImpl(BookRepository bookRepository,
                           UserContextDTO userContextDTO,
                           AuthorService authorService,
                           LocalStorageService localStorageService) {
        this.bookRepository = bookRepository;
        this.userContextDTO = userContextDTO;
        this.localStorageService = localStorageService;
        this.authorService = authorService;
    }


    @Override
    public Page<BookResponseDTO> booksByAuthor(Pageable pageable) {

        var authorId = userContextDTO.getAuthorId();
        Author author = authorService.findAuthorById(authorId);

        Page<Book> booksPage =
                bookRepository.findAllByAuthorAndIsDeletedFalse(author, pageable);

        return booksPage.map(book -> {

            List<ChapterResponseDTO> chaptersDTO = new ArrayList<>();

            if (!ObjectUtils.isEmpty(book.getChapters())) {
                book.getChapters().forEach(chapter ->
                        chaptersDTO.add(Chapter.toChapterResponseDTO(chapter))
                );
            }

            return Book.toBookResponseDTO(book, chaptersDTO);
        });
    }

    @Override
    @Transactional
    public BookResponseDTO create(BookRequestDTO bookRequestDTO) {
        var authorId = userContextDTO.getAuthorId();
        if(ObjectUtils.isEmpty(authorId)) throw new UsernameNotFoundException("Author not found");
        Author author = authorService.findAuthorById(authorId);
        Book book = Book.toEntity(bookRequestDTO,author,null);
        book.setStatus(BookStatusEnum.CREATED);
        book.setSlug(SlugUtils.toSlug(bookRequestDTO.getTitle()));
        var bookId = bookRepository.save(book).getId();
        if(bookRequestDTO.getCover() != null){
            var coverPath = localStorageService.upload(S3_COVERBOOK_PATH,bookId, bookRequestDTO.getCover());
            book.setCoverPath(coverPath);
        }
        book.setId(bookId);
        bookRepository.save(book);
        return Book.toBookResponseDTO(book,null);
    }

    @Override
    public BookResponseDTO update(String id, BookRequestDTO bookRequestDTO) throws ClassNotFoundException {
        Book book = bookRepository.findById(id).orElseThrow(
                                        ()-> new ClassNotFoundException("Book not found"));
        String coverPath = null;
        if(bookRequestDTO.getCover() != null){
            coverPath = localStorageService.upload(S3_COVERBOOK_PATH,book.getId(), bookRequestDTO.getCover());
            book.setCoverPath(coverPath);
        }

        book.update(bookRequestDTO,coverPath);
        book = bookRepository.save(book);
        return Book.toBookResponseDTO(book,null);
    }

    @Override
    public void delete(String id) throws ClassNotFoundException {
     Book book = bookRepository.findById(id).orElseThrow(()-> new ClassNotFoundException("Book not found"));
     book.setDeleted(true);
     localStorageService.delete(String.format(S3_COVERBOOK_PATH,book.getId(), book.getId()));
     bookRepository.save(book);
    }
}
