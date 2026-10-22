package pl.kuba6000.ae2webintegration.core.http;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.IRequest;
import pl.kuba6000.ae2webintegration.core.http.contract.Authentication;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;

/** Compiled endpoint metadata; a null factory denotes the separately dispatched binary endpoint. */
@Desugar
public record HttpBinding(HttpMethod method, String path, Authentication authentication,
    @Nullable Supplier<? extends IRequest> factory, BiConsumer<Object, InputValues> inputs) {}
