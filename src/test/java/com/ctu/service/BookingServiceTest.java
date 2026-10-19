package com.ctu.service;

import com.ctu.exception.PaymentFailedException;
import com.ctu.exception.RoomNotAvailableException;
import com.ctu.model.BookingReceipt;
import com.ctu.model.Room;
import com.ctu.repository.RoomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService Unit Test Suite")
class BookingServiceTest {

    private static final String ROOM_ID = "R101";
    private static final String CARD = "4111-1111-1111-1111";
    private static final double PRICE = 500_000;

    @Mock
    private RoomRepository roomRepository; // gia lap CSDL

    @Mock
    private PaymentGateway paymentGateway; // gia lap cong thanh toan

    @InjectMocks
    private BookingService bookingService;

    // ---------------------------------------------------------------- Luong thanh cong

    @Test
    @DisplayName("Dat phong thanh cong: phong chuyen sang isAvailable == false, save() va charge() goi dung 1 lan")
    void testBookRoom_Success() {
        // ARRANGE
        Room room = new Room(ROOM_ID, PRICE, true);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(paymentGateway.charge(CARD, 1_500_000)).thenReturn(true);

        // ACT
        BookingReceipt receipt = bookingService.bookRoom(ROOM_ID, 3, CARD);

        // ASSERT: ket qua tra ve
        assertNotNull(receipt);
        assertNotNull(receipt.getBookingId());
        assertFalse(receipt.getBookingId().isEmpty());
        assertEquals(ROOM_ID, receipt.getRoomId());
        assertEquals(1_500_000, receipt.getTotalAmount(), 0.001);
        // ASSERT: trang thai phong
        assertFalse(room.isAvailable(), "Phong phai chuyen sang isAvailable == false sau khi dat");
        // ASSERT: tuong tac voi mock
        verify(roomRepository, times(1)).findById(ROOM_ID);
        verify(paymentGateway, times(1)).charge(CARD, 1_500_000);
        verify(roomRepository, times(1)).save(room);
        verifyNoMoreInteractions(roomRepository, paymentGateway);
    }

    @Test
    @DisplayName("Bien duoi hop le: nights = 1 la so dem nho nhat duoc chap nhan")
    void testBookRoom_MinimumValidNights_Success() {
        Room room = new Room(ROOM_ID, PRICE, true);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(paymentGateway.charge(CARD, PRICE)).thenReturn(true);

        BookingReceipt receipt = bookingService.bookRoom(ROOM_ID, 1, CARD);

        assertEquals(PRICE, receipt.getTotalAmount(), 0.001);
        assertFalse(room.isAvailable());
        verify(paymentGateway, times(1)).charge(CARD, PRICE);
        verify(roomRepository, times(1)).save(room);
    }

    @Test
    @DisplayName("Moi lan dat phong sinh ma booking khac nhau")
    void testBookRoom_GeneratesUniqueBookingIds() {
        Room first = new Room("R1", PRICE, true);
        Room second = new Room("R2", PRICE, true);
        when(roomRepository.findById("R1")).thenReturn(Optional.of(first));
        when(roomRepository.findById("R2")).thenReturn(Optional.of(second));
        when(paymentGateway.charge(anyString(), anyDouble())).thenReturn(true);

        BookingReceipt r1 = bookingService.bookRoom("R1", 1, CARD);
        BookingReceipt r2 = bookingService.bookRoom("R2", 1, CARD);

        assertNotEquals(r1.getBookingId(), r2.getBookingId());
    }

    // ---------------------------------------------------------------- Ngoai le nghiep vu

    @Test
    @DisplayName("Nem RoomNotAvailableException khi findById() tra ve Optional.empty()")
    void testBookRoom_RoomNotFound_ThrowsException() {
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        RoomNotAvailableException ex = assertThrows(RoomNotAvailableException.class,
                () -> bookingService.bookRoom(ROOM_ID, 2, CARD));

        assertEquals("Khong tim thay phong: " + ROOM_ID, ex.getMessage());
        verify(roomRepository, times(1)).findById(ROOM_ID);
        verify(roomRepository, never()).save(any());
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("Nem RoomNotAvailableException khi room.isAvailable() == false")
    void testBookRoom_RoomAlreadyBooked_ThrowsException() {
        Room bookedRoom = new Room(ROOM_ID, PRICE, false);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(bookedRoom));

        RoomNotAvailableException ex = assertThrows(RoomNotAvailableException.class,
                () -> bookingService.bookRoom(ROOM_ID, 2, CARD));

        assertEquals("Phong da duoc dat truoc do: " + ROOM_ID, ex.getMessage());
        assertFalse(bookedRoom.isAvailable());
        verify(roomRepository, never()).save(any());
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("Nem PaymentFailedException khi charge() tra ve false - phong van con trong, khong luu CSDL")
    void testBookRoom_PaymentFailed_ThrowsException() {
        Room room = new Room(ROOM_ID, PRICE, true);
        when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(paymentGateway.charge(CARD, 1_000_000)).thenReturn(false);

        PaymentFailedException ex = assertThrows(PaymentFailedException.class,
                () -> bookingService.bookRoom(ROOM_ID, 2, CARD));

        assertEquals("Thanh toan qua the credit card that bai!", ex.getMessage());
        assertTrue(room.isAvailable(), "Thanh toan that bai thi phong khong duoc bi khoa");
        verify(paymentGateway, times(1)).charge(CARD, 1_000_000);
        verify(roomRepository, never()).save(any());
    }

    // ---------------------------------------------------------------- Tham so bien

    @ParameterizedTest(name = "nights = {0}")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("Nem IllegalArgumentException khi nights <= 0")
    void testBookRoom_InvalidNights_ThrowsException(int nights) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> bookingService.bookRoom(ROOM_ID, nights, CARD));

        assertEquals("Tham so dat phong khong hop le!", ex.getMessage());
        verifyNoInteractions(roomRepository, paymentGateway);
    }

    @ParameterizedTest(name = "creditCard = \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    @DisplayName("Nem IllegalArgumentException khi creditCard null, rong hoac chi gom khoang trang")
    void testBookRoom_NullOrEmptyCreditCard_ThrowsException(String creditCard) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> bookingService.bookRoom(ROOM_ID, 2, creditCard));

        assertEquals("Tham so dat phong khong hop le!", ex.getMessage());
        verifyNoInteractions(roomRepository, paymentGateway);
    }

    @Test
    @DisplayName("Nem IllegalArgumentException khi roomId == null")
    void testBookRoom_NullRoomId_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> bookingService.bookRoom(null, 2, CARD));

        verifyNoInteractions(roomRepository, paymentGateway);
    }
}
