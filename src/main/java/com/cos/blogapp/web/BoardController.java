package com.cos.blogapp.web;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.cos.blogapp.domain.board.Board;
import com.cos.blogapp.domain.board.BoardRepository;
import com.cos.blogapp.domain.comment.Comment;
import com.cos.blogapp.domain.comment.CommentRepository;
import com.cos.blogapp.domain.user.User;
import com.cos.blogapp.handler.ex.MyAsyncNotFoundException;
import com.cos.blogapp.handler.ex.MyNotFoundException;
import com.cos.blogapp.util.Script;
import com.cos.blogapp.web.dto.BoardSaveReqDto;
import com.cos.blogapp.web.dto.CMRespDto;
import com.cos.blogapp.web.dto.CommentSaveReqDto;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Controller // 컴퍼넌트 스캔(스프링) IoC
public class BoardController {
	private final BoardRepository boardRepository;
	private final CommentRepository commentRepository;
	private final HttpSession session;
	
	@PostMapping("/board/{boardId}/comment")
	public String commentSave(@PathVariable int boardId, CommentSaveReqDto dto) {
		// 1. DTO로 데이터 받기
		// 2. Comment 객체 만들기(빈객체 생성)
		Comment comment = new Comment();
		
		// 3. Comment 객체에 값 추가하기, id: X, content: DTO값, user: 세션값, board: boardId로 findById하기
		User principal = (User)session.getAttribute("principal");
		Board boardEntity = boardRepository.findById(boardId)
				.orElseThrow(() -> new MyNotFoundException("해당 게시글을 찾을 수 없습니다"));
		
		comment.setContent(dto.getContent());
		comment.setUser(principal);
		comment.setBoard(boardEntity);
		
		// 4. save 하기
		commentRepository.save(comment);
		
		return "redirect:/board/" +  boardId;
	}
	
	@PutMapping("/board/{id}")                                                                         // JSON을 JAVA로 받아줌                  // 무조건 바인딩리절트는 dto옆에!
	public @ResponseBody CMRespDto<String> update(@PathVariable int id, @Valid @RequestBody BoardSaveReqDto dto, BindingResult bindingResult){
		User principal = (User)session.getAttribute("principal");
		// 이러한 공통로직을 AOP처리로 따로 빼면 좋음(인증, 권한, 유효성 검사)
		// 인증
		if(principal == null) { // 로그인 안됨
			throw new MyAsyncNotFoundException("인증이 되지 않았습니다");
		} 
		
		// 권한
		Board boardEntity = boardRepository.findById(id)
				.orElseThrow(() -> new MyAsyncNotFoundException("해당 게시글을 찾을 수 없습니다"));
		if(principal.getId() != boardEntity.getUser().getId()) {
			throw new MyAsyncNotFoundException("해당 게시글의 주인이 아닙니다");
		}
		
		// 유효성 검사
		if(bindingResult.hasErrors()) {  // 에러가 터졌을 때
			Map<String, String> errorMap = new HashMap<>();
			for(FieldError error : bindingResult.getFieldErrors()) {
				errorMap.put(error.getField(), error.getDefaultMessage());
			}
			throw new MyAsyncNotFoundException(errorMap.toString());
		}
		
		Board board = dto.toEntity(principal);
		board.setId(id); // update의 핵심
		
		boardRepository.save(board);
		return new CMRespDto<>(1, "업데이트 성공", null);
	}
	
	// 모델의 접근을 안하면 인증/권한 굳이 필요없음(수정에서만 막아주면 됨)
	@GetMapping("/board/{id}/updateForm")
	public String boardUpdateForm(@PathVariable int id, Model model) {
		// 게시글 정보를 가지고 가야함
		Board boardEntity = boardRepository.findById(id)
				.orElseThrow(() -> new MyNotFoundException(id + "번호의 게시글을 찾을 수 없습니다"));
		
		model.addAttribute("boardEntity",boardEntity); // 클라이언트에서 응답되면 데이터 사라짐
		return "board/updateForm";
	}
	
	// API(AJAX)요청
	@DeleteMapping("/board/{id}")
	public @ResponseBody CMRespDto<String> deleteById(@PathVariable int id) {
		// 인증이 된 사람만 함수 접근 가능(로그인 된 사람)
		User principal = (User)session.getAttribute("principal");
		if(principal == null) {
			throw new MyAsyncNotFoundException("인증이 되지 않았습니다");
		}
		// 권한이 있는 사람만 함수 접근 가능(principal.id == {id})
		Board boardEntity = boardRepository.findById(id)
				.orElseThrow(() -> new MyAsyncNotFoundException("해당글을 찾을 수 없습니다"));
		
		if(principal.getId() != boardEntity.getUser().getId()) {
			throw new MyAsyncNotFoundException("해당글을 삭제할 권한이 없습니다");
		}
		
		try {
			boardRepository.deleteById(id); // 오류발생??(id가 없으면)			
		} catch (Exception e) {
			throw new MyAsyncNotFoundException(id + "를 찾을 수 없어서 삭제할 수 없어요");
		}
		
		return new CMRespDto<String>(1, "성공",  null) ; // 데이터리턴 String -> text/plain
	}
	
	//  쿼리스트링, 패스var -> 디비 where에 걸리는 친구들
	// 1. 컨트롤러 선정 2. HttpMethod 선정 3. 받을 데이터가 있는지(body, 쿼리스트링, 패스var)
	// 4. 디비에 접근을 해야하면 Model접근하기 orElse Model에 접근할 필요가 없음
	@GetMapping("/board/{id}")
	public String detail(@PathVariable int id, Model model) {
		// select * from board where id =:id
		
		// 1. orElse는 값을 찾으면 Board가 리턴, 못찾으면(괄호 안 내용 리턴)
//		Board boardEntity = boardRepository.findById(id)
//				.orElse(new Board(100, "글없어요", "글없어요", null));
		
		// 2. orElseThrow
		Board boardEntity = boardRepository.findById(id)
//				.orElseThrow(new Supplier<MyNotFoundException>() { // 어떤 익셉션이 발생하든 내가 정의한 익셉션으로 던져줄 수 있음
//					@Override
//					public MyNotFoundException get() {
//						return new MyNotFoundException(id + "를 찾을 수 없습니다");
//					}
//				}); 
				// 람다식으로 변형
				.orElseThrow(() -> new MyNotFoundException(id + "를 찾을 수 없습니다")
				); // 중괄호 안넣으면 무조건 리턴(한줄 시)
		
		model.addAttribute("boardEntity", boardEntity);
		return "board/detail";
	}
	
	@PostMapping("/board")// 보드 모델에 저장할 것임
	// x-www-form-urlencoded 이 타입만 받을 수 있음
	public @ResponseBody String save(@Valid BoardSaveReqDto dto, BindingResult bindingResult) {
		User principal = (User)session.getAttribute("principal");
		
		// 인증체크
		if(principal == null) { // 로그인 안됨
//			return Script.back("잘못된 접근입니다");
			return Script.href("/loginForm", "잘못된 접근입니다");
		} // postman에서 접근 불가(로그인 인증해야 되서)
		
		if(bindingResult.hasErrors()) {  // 에러가 터졌을 때
			Map<String, String> errorMap = new HashMap<>();
			for(FieldError error : bindingResult.getFieldErrors()) {
				errorMap.put(error.getField(), error.getDefaultMessage());
			}
			return Script.back(errorMap.toString());
		}
		dto.setContent(dto.getContent().replaceAll("<p>", ""));
		dto.setContent(dto.getContent().replaceAll("</p>", "")); // p태그 날리기
//		User user = new User();
//		user.setId(3);
//		boardRepository.save(dto.toEntity(user));		
		
		boardRepository.save(dto.toEntity(principal));
//		return "redirect:/";
		return Script.href("/", "글쓰기 성공");
	}
	
	@GetMapping("/board/saveForm")
	public String saveForm() {
	
		return "board/saveForm";
	}
	
	@GetMapping("/board") // 보드 모델에서 가져올 것임
	public String home (Model model, int page) {		
		// 페이징 & 정렬	
		PageRequest pageRequest = PageRequest.of(page, 3, Sort.by(Sort.Direction.DESC, "id"));
		     
		Page<Board> boardsEntity = boardRepository.findAll(pageRequest);
		// 영속화된 오브젝트 boardsEntity
//		List<Board> boardsEntity = boardRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
		// 지연로딩(lazy)은 board만 select하지만 영속화된 오브젝트(boardsEntity)에서 쓸 때 User을 select함
		// eager은 미리 땡겨옴 -> 둘다 select(default)
		// 한 건만 select할 때는 eager, 여러 건을 select하면 lazy(부하 때문)
		
		model.addAttribute("boardsEntity",boardsEntity);
//		System.out.println(boardsEntity.get(0).getUser().getUsername());
		return "board/list";
	}
}
