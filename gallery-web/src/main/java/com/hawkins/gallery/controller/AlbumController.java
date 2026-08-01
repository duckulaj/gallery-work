package com.hawkins.gallery.controller;

import java.io.IOException;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.hawkins.gallery.service.AlbumService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    @PostMapping("/albums")
    public void create(@RequestParam String name,
                       @RequestParam(required = false) String parentId,
                       @RequestParam(required = false) String returnFolderId,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                       HttpServletResponse response) throws IOException {
        var album = albumService.create(name, parentId);
        String target = (returnFolderId != null && !returnFolderId.isBlank())
                ? "/folders/" + returnFolderId : "/folders/" + album.getId();
        if (hxRequest != null) {
            response.setHeader("HX-Redirect", target);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else {
            response.sendRedirect(target);
        }
    }

    @DeleteMapping("/albums/{id}")
    public void delete(@PathVariable String id,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                       HttpServletResponse response) throws IOException {
        albumService.delete(id);
        if (hxRequest != null) {
            response.setHeader("HX-Redirect", "/");
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else {
            response.sendRedirect("/");
        }
    }

    @PostMapping("/albums/{id}/rename")
    public void rename(@PathVariable String id,
                       @RequestParam String name,
                       @RequestParam(required = false) String returnFolderId,
                       @RequestHeader(value = "HX-Request", required = false) String hxRequest,
                       HttpServletResponse response) throws IOException {
        albumService.rename(id, name);
        String target = (returnFolderId != null && !returnFolderId.isBlank())
                ? "/folders/" + returnFolderId : "/";
        if (hxRequest != null) {
            response.setHeader("HX-Redirect", target);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else {
            response.sendRedirect(target);
        }
    }
}
